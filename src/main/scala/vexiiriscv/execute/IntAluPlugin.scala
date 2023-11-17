// SPDX-FileCopyrightText: 2023 "Everybody"
//
// SPDX-License-Identifier: MIT

package vexiiriscv.execute

import spinal.core._
import spinal.lib.Flow
import spinal.lib.misc.pipeline._
import vexiiriscv.Global
import vexiiriscv.decode._
import vexiiriscv.riscv.{Riscv, Rvi}

object IntAluPlugin extends AreaObject {
  val AluBitwiseCtrlEnum = new SpinalEnum(binarySequential) {
    val XOR, OR, AND = newElement()
  }
  val AluCtrlEnum = new SpinalEnum(binarySequential) {
    val ADD_SUB, SLT_SLTU, BITWISE = newElement()
  }

  val ALU_BITWISE_CTRL = SignalKey(AluBitwiseCtrlEnum())
  val ALU_CTRL = SignalKey(AluCtrlEnum())
  val ALU_RESULT = SignalKey(Bits(Riscv.XLEN bits))
}

class IntAluPlugin(val euId : String,
                   var staticLatency : Boolean = true,
                   var aluStage : Int = 0,
                   var writebackAt : Int = 0) extends ExecutionUnitElementSimple(euId, staticLatency)  {
  import IntAluPlugin._


  val logic = during build new Logic(0){
    import SrcKeys._

    val ace = AluCtrlEnum
    val abce = AluBitwiseCtrlEnum

    add(Rvi.ADD , List(Op.ADD   , SRC1.RF, SRC2.RF), DecodeList(ALU_CTRL -> ace.ADD_SUB ))
    add(Rvi.SUB , List(Op.SUB   , SRC1.RF, SRC2.RF), DecodeList(ALU_CTRL -> ace.ADD_SUB ))
    add(Rvi.SLT , List(Op.LESS  , SRC1.RF, SRC2.RF), DecodeList(ALU_CTRL -> ace.SLT_SLTU))
    add(Rvi.SLTU, List(Op.LESS_U, SRC1.RF, SRC2.RF), DecodeList(ALU_CTRL -> ace.SLT_SLTU))
    add(Rvi.XOR , List(           SRC1.RF, SRC2.RF), DecodeList(ALU_CTRL -> ace.BITWISE , ALU_BITWISE_CTRL -> abce.XOR ))
    add(Rvi.OR  , List(           SRC1.RF, SRC2.RF), DecodeList(ALU_CTRL -> ace.BITWISE , ALU_BITWISE_CTRL -> abce.OR  ))
    add(Rvi.AND , List(           SRC1.RF, SRC2.RF), DecodeList(ALU_CTRL -> ace.BITWISE , ALU_BITWISE_CTRL -> abce.AND ))

    add(Rvi.ADDI , List(Op.ADD   , SRC1.RF, SRC2.I), DecodeList(ALU_CTRL -> ace.ADD_SUB ))
    add(Rvi.SLTI , List(Op.LESS  , SRC1.RF, SRC2.I), DecodeList(ALU_CTRL -> ace.SLT_SLTU))
    add(Rvi.SLTIU, List(Op.LESS_U, SRC1.RF, SRC2.I), DecodeList(ALU_CTRL -> ace.SLT_SLTU))
    add(Rvi.XORI , List(           SRC1.RF, SRC2.I), DecodeList(ALU_CTRL -> ace.BITWISE , ALU_BITWISE_CTRL -> abce.XOR ))
    add(Rvi.ORI  , List(           SRC1.RF, SRC2.I), DecodeList(ALU_CTRL -> ace.BITWISE , ALU_BITWISE_CTRL -> abce.OR  ))
    add(Rvi.ANDI , List(           SRC1.RF, SRC2.I), DecodeList(ALU_CTRL -> ace.BITWISE , ALU_BITWISE_CTRL -> abce.AND ))

    add(Rvi.LUI,   List(Op.SRC1  , SRC1.U)         , DecodeList(ALU_CTRL -> ace.ADD_SUB))
    add(Rvi.AUIPC, List(Op.ADD   , SRC1.U, SRC2.PC), DecodeList(ALU_CTRL -> ace.ADD_SUB))

    if(Riscv.XLEN.get == 64){
      add(Rvi.ADDW ,  List(Op.ADD   , SRC1.RF, SRC2.RF), DecodeList(ALU_CTRL -> ace.ADD_SUB ))
      add(Rvi.SUBW , List(Op.SUB   , SRC1.RF, SRC2.RF), DecodeList(ALU_CTRL -> ace.ADD_SUB ))
      add(Rvi.ADDIW , List(Op.ADD   , SRC1.RF, SRC2.I), DecodeList(ALU_CTRL -> ace.ADD_SUB ))

      for(op <- List(Rvi.ADDW, Rvi.SUBW, Rvi.ADDIW)){
        //signExtend(op, 31)
        ???
      }
    }

    eu.release()

    val processCtrl = eu.ctrl(aluStage)
    val process = new processCtrl.Area {
      val ss = SrcStageables

      val bitwise = ALU_BITWISE_CTRL.mux(
        AluBitwiseCtrlEnum.AND  -> (ss.SRC1 & ss.SRC2),
        AluBitwiseCtrlEnum.OR   -> (ss.SRC1 | ss.SRC2),
        AluBitwiseCtrlEnum.XOR  -> (ss.SRC1 ^ ss.SRC2)
      )

      val result = ALU_CTRL.mux(
        AluCtrlEnum.BITWISE  -> bitwise,
        AluCtrlEnum.SLT_SLTU -> S(U(ss.LESS, Riscv.XLEN bits)),
        AluCtrlEnum.ADD_SUB  -> this(ss.ADD_SUB)
      )

      ALU_RESULT := result.asBits
    }

//    val writeback = new ExecuteArea(writebackAt) {
//      import stage._
//      wb.payload := ALU_RESULT
//    }
  }
}
