package vexiiriscv.execute

import spinal.core._
import spinal.core.fiber.{Lock, Lockable}
import spinal.lib._
import spinal.lib.logic.{DecodingSpec, Masked}
import spinal.lib.misc.pipeline._
import spinal.lib.misc.plugin.FiberPlugin
import vexiiriscv.Global
import vexiiriscv.decode.Decode
import vexiiriscv.execute.ExecuteUnitPlugin.SEL
import vexiiriscv.misc.{CtrlPipelinePlugin, PipelineService}
import vexiiriscv.regfile.RegfileService
import vexiiriscv.riscv.{MicroOp, RD, RegfileSpec, RfAccess, RfRead, RfResource}
import vexiiriscv.schedule.{Ages, DispatchPlugin}

import scala.collection.mutable
import scala.collection.mutable.ArrayBuffer


trait ExecuteUnitCtrlApi{
  def getCtrl: ExecuteUnitCtrl
  def euId : String
  private val _c = getCtrl
  import _c._

  def isValid: Bool = SEL
  def isFiring: Bool = SEL && _c.on.isReady

  def apply[T <: Data](that: Payload[T]): T = _c.on.apply(that, euId)
  def apply[T <: Data](that: Payload[T], subKey : Any): T = _c.on.apply(that, euId + "_" + subKey.toString)
  def insert[T <: Data](that: T): Payload[T] = {
    val p = Payload(that)
    apply(p) := that
    p
  }
  def bypass[T <: Data](that: Payload[T]): T =  _c.on.bypass(that, euId)

  def up = {
    val up = _c.on.up
    new up.Area(euId)
  }
  def down = {
    val down = _c.on.down
    new down.Area(euId)
  }

  implicit def stageablePiped2[T <: Data](stageable: Payload[T]): T = this (stageable)
  class BundlePimper[T <: Bundle](pimped: T) {
    def :=(that: T): Unit = pimped := that
  }

  implicit def bundlePimper[T <: Bundle](stageable: Payload[T]) = new BundlePimper[T](this (stageable))
}

class ExecuteUnitCtrl(val at : Int, val on : CtrlLink, override val euId : String) extends Area with ExecuteUnitCtrlApi{
  override def getCtrl: ExecuteUnitCtrl = this

  class Area extends spinal.core.Area with ExecuteUnitCtrlApi{
    override def getCtrl: ExecuteUnitCtrl = ExecuteUnitCtrl.this
    override def euId: String = ExecuteUnitCtrl.this.euId
  }
}

object ExecuteUnitPlugin extends AreaRoot{
  val SEL = Payload(Bool())
}

class ExecuteUnitPlugin(val euId : String,
                        val priority : Int,
                        override val rfReadAt : Int,
                        val decodeAt : Int,
                        override val executeAt : Int) extends FiberPlugin with ExecuteUnitService with CompletionService{
  lazy val eupp = host[ExecuteUnitPipelinePlugin]
  setupRetain(eupp.pipelineLock)
  withPrefix(euId)

  during setup {
    host.list[RegfileService].foreach(_.elaborationLock.retain())
  }

  override def euName(): String = euId
  override def dispatchPriority: Int = priority
  override def getMicroOp(): Seq[MicroOp] = {
    uopLock.await()
    microOps.keys.toSeq
  }
  override def getMicroOpSpecs(): Iterable[MicroOpSpec] = {
    uopLock.await()
    microOps.values
  }

  val microOps = mutable.LinkedHashMap[MicroOp, MicroOpSpec]()
  def addMicroOp(op : MicroOp): Unit = {
    microOps.getOrElseUpdate(op, new MicroOpSpec(op))
  }

  def setRdSpec(op : MicroOp, data : Payload[Bits], rfReadableAt : Int, bypassesAt : Seq[Int]): Unit = {
    assert(microOps(op).rd.isEmpty)
    microOps(op).rd = Some(RdSpec(data, rfReadableAt + executeAt, bypassesAt.map(_ + executeAt)))
  }

  def setCompletion(op : MicroOp, executeCtrlId : Int): Unit = {
    microOps(op).completion = Some(executeCtrlId + executeAt)
  }

  override def getSpec(op: MicroOp): MicroOpSpec = microOps(op)

  def setDecodingDefault(key: Payload[_ <: BaseType], value: BaseType): Unit = {
    getDecodingSpec(key).setDefault(Masked(value))
  }

  def addDecoding(microOp: MicroOp, values: Seq[(Payload[_ <: BaseType], Any)]): Unit = {
    val op = Masked(microOp.key)
    for ((key, value) <- values) {
      getDecodingSpec(key).addNeeds(op, Masked(value))
    }
  }

  def addDecoding(microOp: MicroOp, head : (Payload[_ <: BaseType], Any), tail : (Payload[_ <: BaseType], Any)*): Unit = {
    addDecoding(microOp, head :: tail.toList)
  }

  def getDecodingSpec(key: Payload[_ <: BaseType]) = decodingSpecs.getOrElseUpdate(key, new DecodingSpec(key))
  val decodingSpecs = mutable.LinkedHashMap[Payload[_ <: BaseType], DecodingSpec[_ <: BaseType]]()

  val rfStageables = mutable.LinkedHashMap[RfResource, Payload[Bits]]()

  def apply(rf: RegfileSpec, access: RfAccess) = getStageable(rf -> access)
  def apply(r: RfResource) = getStageable(r)
  def getStageable(r: RfResource): Payload[Bits] = {
    rfStageables.getOrElseUpdate(r, Payload(Bits(r.rf.width bits)).setName(s"${r.rf.getName()}_${r.access.getName()}"))
  }

  val idToCtrl = mutable.LinkedHashMap[Int, ExecuteUnitCtrl]()
  def ctrl(id : Int)  : ExecuteUnitCtrl = {
    idToCtrl.getOrElseUpdate(id, new ExecuteUnitCtrl(id, eupp.ctrl(id), euId).setCompositeName(this, if(id >= executeAt) "exe" + (id - executeAt) else "dis" + id))
  }
  def execute(id: Int) : ExecuteUnitCtrl = {
    assert(id >= 0)
    ctrl(id + executeAt)
  }


  def getAge(at: Int, prediction: Boolean): Int = Ages.EU + at * Ages.STAGE + prediction.toInt * Ages.PREDICTION
  override def getCompletions(): Seq[Flow[CompletionPayload]] = logic.completions.onCtrl.map(_.port).toSeq

  val logic = during build new Area {
    uopLock.await()
    pipelineLock.await()

    // Generate the register files read + bypass
    val rf = new Area {
      val rfSpecs = rfStageables.keys.map(_.rf).distinctLinked
      val rfPlugins = rfSpecs.map(spec => host.find[RegfileService](_.rfSpec == spec))

      val readCtrl = ctrl(rfReadAt)
      val reads = for ((spec, payload) <- rfStageables) yield new Area {
        // Implement the register file read
        val rfa = Decode.rfaKeys.get(spec.access)
        val rfPlugin = host.find[RegfileService](_.rfSpec == spec.rf)
        val port = rfPlugin.newRead(false)
        port.valid := readCtrl.isValid && readCtrl(rfa.ENABLE) && rfa.is(spec.rf, readCtrl(rfa.RFID))
        port.address := readCtrl(rfa.PHYS)

        // Generate a bypass specification for the regfile readed data
        case class BypassSpec(eu: ExecuteUnitService, nodeId: Int, payload: Payload[Bits])
        val bypassSpecs = mutable.LinkedHashSet[BypassSpec]()
        val eus = host.list[ExecuteUnitService]
        for (eu <- eus; ops = eu.getMicroOp();
             op <- ops; opSpec = eu.getSpec(op)) {
          eu.pipelineLock.await() // Ensure that the eu specification is done
          val sameRf = opSpec.op.resources.exists {
            case RfResource(spec.rf, RD) => true
            case _ => false
          }
          if (sameRf) {
            val rd = opSpec.rd.get
            for (nodeId <- rd.bypassesAt) {
              val bypassSpec = BypassSpec(eu, nodeId, rd.DATA)
              bypassSpecs += bypassSpec
            }
          }
        }

        // Implement the bypass hardware
        val dataCtrl = ctrl(rfReadAt + rfPlugin.readLatency)
        val rfaRd = Decode.rfaKeys.get(RD)
        val bypassSorted = bypassSpecs.toSeq.sortBy(_.nodeId)
        val bypassEnables = Bits(bypassSorted.size + 1 bits)
        for ((b, id) <- bypassSorted.zipWithIndex) {
          val node = b.eu.ctrl(b.nodeId)
          bypassEnables(id) := node(rfaRd.ENABLE) && node(rfaRd.PHYS) === dataCtrl(rfa.PHYS) && node(rfaRd.RFID) === dataCtrl(rfa.RFID)
        }
        bypassEnables.msb := True
        val sel = OHMasking.firstV2(bypassEnables)
        dataCtrl(payload) := OHMux.or(sel, bypassSorted.map(b => b.eu.ctrl(b.nodeId)(b.payload)) :+ port.data, true)
      }
    }

    // Implement completion logic
    val completions = new Area{
      val groups = getMicroOpSpecs().groupBy(_.completion)

      val onCtrl = for((at, uops) <- groups if at.exists(_ != -1)) yield new Area {
        val c = ctrl(at.get)
        val ENABLE = Payload(Bool())
        setDecodingDefault(ENABLE, False)
        for(uop <- uops) addDecoding(uop.op, ENABLE -> True)
        val port = Flow(CompletionPayload())
        port.valid := c.isFiring && c(ENABLE)
        port.hartId := c(Global.HART_ID)
        port.microOpId := c(Decode.UOP_ID)
      }
    }

    // Implement some UOP decoding for the execute's plugin usages
    val decodeCtrl = ctrl(decodeAt)
    val decoding = new decodeCtrl.Area {
      val coverAll = getMicroOp().map(e => Masked(e.key))
      for ((key, spec) <- decodingSpecs) {
        key.assignFromBits(spec.build(Decode.UOP, coverAll).asBits)
      }
    }

    for(ctrlId <- 1 until idToCtrl.keys.max){
      val c = ctrl(ctrlId)
      c.up(SEL).setAsReg().init(False)
    }

    host.list[RegfileService].foreach(_.elaborationLock.release())

    eupp.pipelineLock.release()
  }
}
