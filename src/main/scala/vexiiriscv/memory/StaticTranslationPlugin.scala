package vexiiriscv.memory

import spinal.core._
import spinal.core.fiber.Retainer
import spinal.lib._
import spinal.lib.misc.pipeline._
import spinal.lib.misc.plugin._
import vexiiriscv.Global._
import vexiiriscv.riscv.Riscv

import scala.collection.mutable.ArrayBuffer

class StaticTranslationPlugin(var physicalWidth: Int,
                              var ioRange: UInt => Bool,
                              var fetchRange: UInt => Bool) extends FiberPlugin with AddressTranslationService {
  override def mayNeedRedo: Boolean = false
  override def newStorage(pAny: Any): Any = { }
  override def getStorageId(s: Any): Int = 0
  override def getStorageIdWidth(): Int = 0

  case class PortSpec(stages: Seq[NodeBaseApi],
                      preAddress: Payload[UInt],
                      forcePhysical: Payload[Bool],
                      usage: AddressTranslationPortUsage,
                      rsp: AddressTranslationRsp)

  val portSpecs = ArrayBuffer[PortSpec]()
  override def newTranslationPort(stages: Seq[NodeBaseApi],
                                  rawAddress: Payload[UInt],
                                  forcePhysical: Payload[Bool],
                                  usage: AddressTranslationPortUsage,
                                  portSpec: Any,
                                  storageSpec: Any): AddressTranslationRsp = {
    portSpecs.addRet(
      new PortSpec(
        stages = stages,
        preAddress = rawAddress,
        forcePhysical = forcePhysical,
        usage = usage,
        rsp = new AddressTranslationRsp(this, 0)
      )
    ).rsp
  }


  val logic = during build new Area {
    PHYSICAL_WIDTH.set(physicalWidth)
    VIRTUAL_WIDTH.set(physicalWidth)
    MIXED_WIDTH.set(physicalWidth)
    PC_WIDTH.set(physicalWidth)
    TVAL_WIDTH.set(physicalWidth)

    portsLock.await()
    assert(refillPorts.isEmpty)

    regionRetainer.await()
    val ports = for(spec <- portSpecs) yield new Area{
      val node = spec.stages.last

      import node._
      import spec.rsp.keys._

      REDO := False
      TRANSLATED := spec.preAddress.resized //PC RESIZED
      IO := ioRange(TRANSLATED)
      ALLOW_EXECUTE := True
      ALLOW_READ := True
      ALLOW_WRITE := True
      PAGE_FAULT := False
      ACCESS_FAULT := False
      ALLOW_EXECUTE clearWhen (!fetchRange(TRANSLATED))
    }
  }
}