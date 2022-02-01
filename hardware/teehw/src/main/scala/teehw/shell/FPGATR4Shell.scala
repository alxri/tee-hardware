package uec.teehardware.shell

import chisel3._
import chisel3.util._
import chisel3.experimental.{Analog, IO, attach}
import freechips.rocketchip.diplomacy._
import freechips.rocketchip.util._
import chipsalliance.rocketchip.config.Parameters
import freechips.rocketchip.subsystem._
import freechips.rocketchip.tilelink._
import sifive.blocks.devices.spi.{SPIFlashParams, SPIParams}
import uec.teehardware.macros._
import uec.teehardware._
import uec.teehardware.devices.sdram.SDRAMKey

class HSMCTR4(val on1: Boolean = true, val on2: Boolean = true) extends Bundle {
  val CLKIN0 = Input(Bool())
  val CLKIN_n1 = Input(Bool())
  val CLKIN_n2 = Input(Bool())
  val CLKIN_p1 = Input(Bool())
  val CLKIN_p2 = Input(Bool())
  val D = Vec(4, Analog(1.W))
  val OUT0 = Analog(1.W)
  val OUT_n1 = on1.option(Analog(1.W))
  val OUT_p1 = on1.option(Analog(1.W))
  val OUT_n2 = on2.option(Analog(1.W))
  val OUT_p2 = on2.option(Analog(1.W))
  val RX_n = Vec(17, Analog(1.W))
  val RX_p = Vec(17, Analog(1.W))
  val TX_n = Vec(17, Analog(1.W))
  val TX_p = Vec(17, Analog(1.W))
}

trait FPGATR4ChipShell {
  // This trait only contains the connections that are supposed to be handled by the chip
  implicit val p: Parameters

  ///////// LED /////////
  val LED = IO(Output(Bits((3 + 1).W)))

  ///////// SW /////////
  val SW = IO(Input(Bits((3 + 1).W)))

  ///////// FAN /////////
  val FAN_CTRL = IO(Output(Bool()))
  FAN_CTRL := true.B

  //////////// HSMC_A //////////
  val HSMA = IO(new HSMCTR4)

  //////////// HSMC_B //////////
  val HSMB = IO(new HSMCTR4)

  //////////// HSMC_C / GPIO //////////
  val GPIO0_D = IO(Vec(35+1, Analog(1.W)))
  val GPIO1_D = IO(Vec(35+1, Analog(1.W)))

  //////////// HSMC_D //////////
  val HSMD = IO(new HSMCTR4(on1 = false))

  //////////// HSMC_E //////////
  val HSME = IO(new HSMCTR4(on1 = false))

  //////////// HSMC_F //////////
  val HSMF = IO(new HSMCTR4(on1 = false, on2 = false))
}

trait FPGATR4ClockAndResetsAndDDR {
  // This trait only contains clocks and resets exclusive for the FPGA
  implicit val p: Parameters

  ///////// CLOCKS /////////
  val OSC_50_BANK1 = IO(Input(Clock()))
  val OSC_50_BANK3 = IO(Input(Clock()))
  val OSC_50_BANK4 = IO(Input(Clock()))
  val OSC_50_BANK7 = IO(Input(Clock()))
  val OSC_50_BANK8 = IO(Input(Clock()))
  val SMA_CLKIN = IO(Input(Clock()))
  val SMA_CLKOUT = IO(Analog(1.W))
  val SMA_CLKOUT_n = IO(Analog(1.W))
  val SMA_CLKOUT_p = IO(Analog(1.W))

  ///////// BUTTON /////////
  val BUTTON = IO(Input(Bits((3 + 1).W)))

  //////////// mem //////////
  def memEnable: Boolean = true
  val mem_a = memEnable.option(IO(Output(Bits((15 + 1).W))))
  val mem_ba = memEnable.option(IO(Output(Bits((2 + 1).W))))
  val mem_cas_n = memEnable.option(IO(Output(Bool())))
  val mem_cke = memEnable.option(IO(Output(Bits((1 + 1).W))))
  val mem_ck = memEnable.option(IO(Output(Bits((0 + 1).W)))) // NOTE: Is impossible to do [0:0]
  val mem_ck_n = memEnable.option(IO(Output(Bits((0 + 1).W)))) // NOTE: Is impossible to do [0:0]
  val mem_cs_n = memEnable.option(IO(Output(Bits((1 + 1).W))))
  val mem_dm = memEnable.option(IO(Output(Bits((7 + 1).W))))
  val mem_dq = memEnable.option(IO(Analog((63 + 1).W)))
  val mem_dqs = memEnable.option(IO(Analog((7 + 1).W)))
  val mem_dqs_n = memEnable.option(IO(Analog((7 + 1).W)))
  val mem_odt = memEnable.option(IO(Output(Bits((1 + 1).W))))
  val mem_ras_n = memEnable.option(IO(Output(Bool())))
  val mem_reset_n = memEnable.option(IO(Output(Bool())))
  val mem_we_n = memEnable.option(IO(Output(Bool())))
  val mem_oct_rdn = memEnable.option(IO(Input(Bool())))
  val mem_oct_rup = memEnable.option(IO(Input(Bool())))
  //val mem_scl = IO(Output(Bool()))
  //val mem_sda = IO(Analog(1.W))
  //val mem_event_n = IO(Input(Bool())) // NOTE: This also appeared, but is not used
}

class FPGATR4Shell(implicit val p :Parameters) extends RawModule
  with FPGATR4ChipShell
  with FPGATR4ClockAndResetsAndDDR {
}

class FPGATR4Internal(chip: Option[WithTEEHWbaseShell with WithTEEHWbaseConnect])(implicit val p :Parameters) extends RawModule
  with FPGAInternals
  with FPGATR4ClockAndResetsAndDDR {
  def outer = chip
  override def otherId: Option[Int] = Some(6)

  val mem_status_local_cal_fail = IO(Output(Bool()))
  val mem_status_local_cal_success = IO(Output(Bool()))
  val mem_status_local_init_done = IO(Output(Bool()))

  val clock = Wire(Clock())
  val reset = Wire(Bool())

  withClockAndReset(clock, reset) {
    // The DDR port
    mem_status_local_cal_fail := false.B
    mem_status_local_cal_success := false.B
    mem_status_local_init_done := false.B

    // Helper function to connect clocks from the Quartus Platform
    def ConnectClockUtil(mod_clock: Clock, mod_reset: Reset, mod_io_qport: QuartusIO, mod_io_ckrst: Bundle with QuartusClocksReset) = {
      val reset_to_sys = ResetCatchAndSync(mod_io_ckrst.qsys_clk, !mod_io_qport.mem_status_local_init_done)
      val reset_to_child = ResetCatchAndSync(mod_io_ckrst.io_clk, !mod_io_qport.mem_status_local_init_done)

      // Clock and reset (for TL stuff)
      clock := mod_io_ckrst.qsys_clk
      reset := reset_to_sys
      sys_clk := mod_io_ckrst.qsys_clk
      mod_clock := mod_io_ckrst.qsys_clk
      mod_reset := reset_to_sys
      rst_n := !reset_to_sys
      jrst_n := !reset_to_sys
      usbClk.foreach(_ := mod_io_ckrst.usb_clk)
      sdramclock.foreach(_ := mod_io_ckrst.qsys_clk)

      // Async clock connections
      aclocks.foreach { aclocks =>
        println(s"Connecting async clocks by default =>")
        (aclocks zip namedclocks).foreach { case (aclk, nam) =>
          println(s"  Detected clock ${nam}")
          if(nam.contains("mbus")) {
            p(SbusToMbusXTypeKey) match {
              case _: AsynchronousCrossing =>
                aclk := mod_io_ckrst.io_clk
                println("    Connected to io_clk")
                mod_clock := mod_io_ckrst.io_clk
                mod_reset := reset_to_child
                println("    Quartus Island clock also connected to io_clk")
              case _ =>
                aclk := mod_io_ckrst.qsys_clk
                println("    Connected to qsys_clk")
            }
          }
          else {
            aclk := mod_io_ckrst.qsys_clk
            println("    Connected to qsys_clk")
          }
        }
      }

      // Legacy ChildClock
      ChildClock.foreach { cclk =>
        println("Quartus Island and Child Clock connected to io_clk")
        cclk := mod_io_ckrst.io_clk
        mod_clock := mod_io_ckrst.io_clk
        mod_reset := reset_to_child
      }

      mod_io_ckrst.ddr_ref_clk := OSC_50_BANK1.asUInt()
      mod_io_ckrst.qsys_ref_clk := OSC_50_BANK4.asUInt() // TODO: This is okay?
      mod_io_ckrst.system_reset_n := BUTTON(2)
    }

    // Helper function to connect the DDR from the Quartus Platform
    def ConnectDDRUtil(mod_io_qport: QuartusIO) = {
      mem_a.foreach(_ := mod_io_qport.memory_mem_a)
      mem_ba.foreach(_ := mod_io_qport.memory_mem_ba)
      mem_ck.foreach(_ := mod_io_qport.memory_mem_ck(0)) // Force only 1 line (although the config forces 1 line)
      mem_ck_n.foreach(_ := mod_io_qport.memory_mem_ck_n(0)) // Force only 1 line (although the config forces 1 line)
      mem_cke.foreach(_ := mod_io_qport.memory_mem_cke)
      mem_cs_n.foreach(_ := mod_io_qport.memory_mem_cs_n)
      mem_dm.foreach(_ := mod_io_qport.memory_mem_dm)
      mem_ras_n.foreach(_ := mod_io_qport.memory_mem_ras_n)
      mem_cas_n.foreach(_ := mod_io_qport.memory_mem_cas_n)
      mem_we_n.foreach(_ := mod_io_qport.memory_mem_we_n)
      mem_dq.foreach(attach(_, mod_io_qport.memory_mem_dq))
      mem_dqs.foreach(attach(_, mod_io_qport.memory_mem_dqs))
      mem_dqs_n.foreach( attach(_, mod_io_qport.memory_mem_dqs_n))
      mem_odt.foreach(_ := mod_io_qport.memory_mem_odt)
      mem_reset_n.foreach(_ := mod_io_qport.memory_mem_reset_n.getOrElse(true.B))
      (mod_io_qport.oct.rdn zip mem_oct_rdn).foreach{case(a,b) => a := b}
      (mod_io_qport.oct.rup zip mem_oct_rup).foreach{case(a,b) => a := b}
    }

    val ddrcfg = QuartusDDRConfig(size_ck = 1, is_reset = true)
    
    tlport.foreach { chiptl =>
      // Instance our converter, and connect everything
      val mod = Module(LazyModule(new TLULtoQuartusPlatform(chiptl.params, ddrcfg)).module)

      // Quartus Platform connections
      ConnectDDRUtil(mod.io.qport)

      // TileLink Interface from platform
      // TODO: Make the DDR optional. Need to stop using the Quartus Platform
      mod.io.tlport.a <> chiptl.a
      chiptl.d <> mod.io.tlport.d

      mem_status_local_cal_fail := mod.io.qport.mem_status_local_cal_fail
      mem_status_local_cal_success := mod.io.qport.mem_status_local_cal_success
      mem_status_local_init_done := mod.io.qport.mem_status_local_init_done

      // Clock and reset (for TL stuff)
      ConnectClockUtil(mod.clock, mod.reset, mod.io.qport, mod.io.ckrst)
    }
    (memser zip memserSourceBits).foreach { case(ms, sourceBits) =>
      // Instance our converter, and connect everything
      val mod = Module(LazyModule(new SertoQuartusPlatform(ms.w, sourceBits, ddrcfg)).module)

      // Serial port
      mod.io.serport.flipConnect(ms)

      // Quartus Platform connections
      ConnectDDRUtil(mod.io.qport)

      mem_status_local_cal_fail := mod.io.qport.mem_status_local_cal_fail
      mem_status_local_cal_success := mod.io.qport.mem_status_local_cal_success
      mem_status_local_init_done := mod.io.qport.mem_status_local_init_done

      // Clock and reset (for TL stuff)
      ConnectClockUtil(mod.clock, mod.reset, mod.io.qport, mod.io.ckrst)
    }
    // The external bus (TODO: Doing nothing)
    (extser zip extserSourceBits).foreach { case (es, sourceBits) =>
      val mod = Module(LazyModule(new FPGAMiniSystemDummy(sourceBits)).module)

      // Serial port
      mod.serport.flipConnect(es)
    }
  }
}

class FPGATR4InternalNoChip
(
  val idBits: Int = 6,
  val idExtBits: Int = 6,
  val widthBits: Int = 32,
  val sinkBits: Int = 1
)(implicit p :Parameters) extends FPGATR4Internal(None)(p) {
  override def otherId = Some(6)
  override def tlparam = p(ExtMem).map { A =>
    TLBundleParameters(
      32,
      A.master.beatBytes * 8,
      6,
      1,
      log2Up(log2Ceil(p(MemoryBusKey).blockBytes)+1),
      Seq(),
      Seq(),
      Seq(),
      false)}
  override def aclkn: Option[Int] = None
  override def memserSourceBits: Option[Int] = p(ExtSerMem).map( A => idBits )
  override def extserSourceBits: Option[Int] = p(ExtSerBus).map( A => idExtBits )
  override def namedclocks: Seq[String] = Seq()
  override def issdramclock: Boolean = p(SDRAMKey).nonEmpty
  override def isChildClock: Boolean = (p(SbusToMbusXTypeKey) match {
    case _: AsynchronousCrossing => true
    case _ => false
  })
  override def isRTCclock: Boolean = p(RTCPort)
}

trait WithFPGATR4InternCreate {
  this: FPGATR4Shell =>
  val chip : WithTEEHWbaseShell with WithTEEHWbaseConnect
  val intern = Module(new FPGATR4Internal(Some(chip)))
}

trait WithFPGATR4InternNoChipCreate {
  this: FPGATR4Shell =>
  val intern = Module(new FPGATR4InternalNoChip)
}

trait WithFPGATR4InternConnect {
  this: FPGATR4Shell =>
  val intern: FPGATR4Internal

  // To intern = Clocks and resets
  intern.OSC_50_BANK1 := OSC_50_BANK1
  intern.OSC_50_BANK3 := OSC_50_BANK3
  intern.OSC_50_BANK4 := OSC_50_BANK4
  intern.OSC_50_BANK7 := OSC_50_BANK7
  intern.OSC_50_BANK8 := OSC_50_BANK8
  intern.BUTTON := BUTTON
  intern.SMA_CLKIN := SMA_CLKIN
  attach(SMA_CLKOUT, intern.SMA_CLKOUT)
  attach(SMA_CLKOUT_n, intern.SMA_CLKOUT_n)
  attach(SMA_CLKOUT_p, intern.SMA_CLKOUT_p)

  (mem_a zip intern.mem_a).foreach{case (a,b) => a := b}
  (mem_ba zip intern.mem_ba).foreach{case (a,b) => a := b}
  (mem_ck zip intern.mem_ck).foreach{case (a,b) => a := b}
  (mem_ck_n zip intern.mem_ck_n).foreach{case (a,b) => a := b}
  (mem_cke zip intern.mem_cke).foreach{case (a,b) => a := b}
  (mem_cs_n zip intern.mem_cs_n).foreach{case (a,b) => a := b}
  (mem_dm zip intern.mem_dm).foreach{case (a,b) => a := b}
  (mem_ras_n zip intern.mem_ras_n).foreach{case (a,b) => a := b}
  (mem_cas_n zip intern.mem_cas_n).foreach{case (a,b) => a := b}
  (mem_we_n zip intern.mem_we_n).foreach{case (a,b) => a := b}
  (mem_dq zip intern.mem_dq).foreach{case (a,b) => attach(a,b)}
  (mem_dqs zip intern.mem_dqs).foreach{case (a,b) => attach(a,b)}
  (mem_dqs_n zip intern.mem_dqs_n).foreach{case (a,b) => attach(a,b)}
  (mem_odt zip intern.mem_odt).foreach{case (a,b) => a := b}
  (mem_reset_n zip intern.mem_reset_n).foreach{case (a,b) => a := b}
  (mem_oct_rdn zip intern.mem_oct_rdn).foreach{case (a,b) => b := a}
  (mem_oct_rup zip intern.mem_oct_rup).foreach{case (a,b) => b := a}
}

trait WithFPGATR4PureConnect {
  this: FPGATR4Shell =>
  val chip : WithTEEHWbaseShell with WithTEEHWbaseConnect
  
  def namedclocks: Seq[String] = chip.system.sys.asInstanceOf[HasTEEHWSystemModule].namedclocks
  // This trait connects the chip to all essentials. This assumes no DDR is connected yet
  LED := Cat(chip.gpio_out, BUTTON(2))
  chip.gpio_in := Cat(BUTTON(3), BUTTON(1,0), SW(1,0))
  chip.jtag.jtag_TDI := ALT_IOBUF(GPIO1_D(4))
  chip.jtag.jtag_TMS := ALT_IOBUF(GPIO1_D(6))
  chip.jtag.jtag_TCK := ALT_IOBUF(GPIO1_D(8))
  ALT_IOBUF(GPIO1_D(10), chip.jtag.jtag_TDO)
  chip.uart_rxd := ALT_IOBUF(GPIO1_D(35))	// UART_TXD
  ALT_IOBUF(GPIO1_D(34), chip.uart_txd) // UART_RXD


  // QSPI
  (chip.qspi zip chip.allspicfg).zipWithIndex.foreach {
    case ((qspiport: TEEHWQSPIBundle, _: SPIParams), i: Int) =>
      if (i == 0) {
        // SD IO
        ALT_IOBUF(GPIO0_D(28), qspiport.qspi_sck)
        ALT_IOBUF(GPIO0_D(30), qspiport.qspi_mosi)
        qspiport.qspi_miso := ALT_IOBUF(GPIO0_D(32))
        ALT_IOBUF(GPIO0_D(34), qspiport.qspi_cs(0))
      } else {
        // Non-valid qspi. Just zero it
        qspiport.qspi_miso := false.B
      }
    case ((qspiport: TEEHWQSPIBundle, _: SPIFlashParams), _: Int) =>
      qspiport.qspi_miso := ALT_IOBUF(GPIO1_D(1))
      ALT_IOBUF(GPIO1_D(3), qspiport.qspi_mosi)
      ALT_IOBUF(GPIO1_D(5), qspiport.qspi_cs(0))
      ALT_IOBUF(GPIO1_D(7), qspiport.qspi_sck)
  }
  
  // USB phy connections
  chip.usb11hs.foreach{ case chipport=>
    ALT_IOBUF(GPIO1_D(17), chipport.USBFullSpeed)
    chipport.USBWireDataIn := Cat(ALT_IOBUF(GPIO1_D(24)), ALT_IOBUF(GPIO1_D(26)))
    ALT_IOBUF(GPIO1_D(28), chipport.USBWireCtrlOut)
    ALT_IOBUF(GPIO1_D(16), chipport.USBWireDataOut(0))
    ALT_IOBUF(GPIO1_D(18), chipport.USBWireDataOut(1))
  }

  // TODO Nullify this for now
  chip.sdram.foreach{ sdram =>
    sdram.sdram_data_i := 0.U
  }
}

trait WithFPGATR4Connect extends WithFPGATR4PureConnect 
  with WithFPGATR4InternCreate 
  with WithFPGATR4InternConnect {
  this: FPGATR4Shell =>

  // From intern = Clocks and resets
  intern.connectChipInternals(chip)

  // The rest of the platform connections
  LED := Cat(
    intern.mem_status_local_cal_fail,
    intern.mem_status_local_cal_success,
    intern.mem_status_local_init_done,
    BUTTON(2)
  )
}

object ConnectHSMCGPIO {
  def apply (n: Int, pu: Int, c: Bool, get: Boolean, HSMC: HSMCTR4) = {
    val p:Int = pu match {
      case it if 1 to 10 contains it => pu - 1
      case it if 13 to 28 contains it => pu - 3
      case it if 31 to 40 contains it => pu - 5
      case _ => throw new RuntimeException(s"J${n}_${pu} is a VDD or a GND")
    }
    n match {
      case 0 =>
        p match {
          case 0 => if(get) c := HSMC.CLKIN_n2 else throw new RuntimeException(s"GPIO${n}_${p} can only be input")
          case 1 => if(get) c := ALT_IOBUF(HSMC.RX_n(16)) else ALT_IOBUF(HSMC.RX_n(16), c)
          case 2 => if(get) c := HSMC.CLKIN_p2 else throw new RuntimeException(s"GPIO${n}_${p} can only be input")
          case 3 => if(get) c := ALT_IOBUF(HSMC.RX_p(16)) else ALT_IOBUF(HSMC.RX_p(16), c)
          case 4 => if(get) c := ALT_IOBUF(HSMC.TX_n(16)) else ALT_IOBUF(HSMC.TX_n(16), c)
          case 5 => if(get) c := ALT_IOBUF(HSMC.RX_n(15)) else ALT_IOBUF(HSMC.RX_n(15), c)
          case 6 => if(get) c := ALT_IOBUF(HSMC.TX_p(16)) else ALT_IOBUF(HSMC.TX_p(16), c)
          case 7 => if(get) c := ALT_IOBUF(HSMC.RX_p(15)) else ALT_IOBUF(HSMC.RX_p(15), c)
          case 8 => if(get) c := ALT_IOBUF(HSMC.TX_n(15)) else ALT_IOBUF(HSMC.TX_n(15), c)
          case 9 => if(get) c := ALT_IOBUF(HSMC.RX_n(14)) else ALT_IOBUF(HSMC.RX_n(14), c)
          case 10 => if(get) c := ALT_IOBUF(HSMC.TX_p(15)) else ALT_IOBUF(HSMC.TX_p(15), c)
          case 11 => if(get) c := ALT_IOBUF(HSMC.RX_p(14)) else ALT_IOBUF(HSMC.RX_p(14), c)
          case 12 => if(get) c := ALT_IOBUF(HSMC.TX_n(14)) else ALT_IOBUF(HSMC.TX_n(14), c)
          case 13 => if(get) c := ALT_IOBUF(HSMC.RX_n(13)) else ALT_IOBUF(HSMC.RX_n(13), c)
          case 14 => if(get) c := ALT_IOBUF(HSMC.TX_p(14)) else ALT_IOBUF(HSMC.TX_p(14), c)
          case 15 => if(get) c := ALT_IOBUF(HSMC.RX_p(13)) else ALT_IOBUF(HSMC.RX_p(13), c)
          case 16 => if(get) c := ALT_IOBUF(HSMC.OUT_n2.get) else ALT_IOBUF(HSMC.OUT_n2.get, c)
          case 17 => if(get) c := ALT_IOBUF(HSMC.RX_n(12)) else ALT_IOBUF(HSMC.RX_n(12), c)
          case 18 => if(get) c := ALT_IOBUF(HSMC.OUT_p2.get) else ALT_IOBUF(HSMC.OUT_p2.get, c)
          case 19 => if(get) c := ALT_IOBUF(HSMC.RX_p(12)) else ALT_IOBUF(HSMC.RX_p(12), c)
          case 20 => if(get) c := ALT_IOBUF(HSMC.TX_n(13)) else ALT_IOBUF(HSMC.TX_n(13), c)
          case 21 => if(get) c := ALT_IOBUF(HSMC.RX_n(11)) else ALT_IOBUF(HSMC.RX_n(11), c)
          case 22 => if(get) c := ALT_IOBUF(HSMC.TX_p(13)) else ALT_IOBUF(HSMC.TX_p(13), c)
          case 23 => if(get) c := ALT_IOBUF(HSMC.RX_p(11)) else ALT_IOBUF(HSMC.RX_p(11), c)
          case 24 => if(get) c := ALT_IOBUF(HSMC.TX_n(12)) else ALT_IOBUF(HSMC.TX_n(12), c)
          case 25 => if(get) c := ALT_IOBUF(HSMC.RX_n(10)) else ALT_IOBUF(HSMC.RX_n(10), c)
          case 26 => if(get) c := ALT_IOBUF(HSMC.TX_p(12)) else ALT_IOBUF(HSMC.TX_p(12), c)
          case 27 => if(get) c := ALT_IOBUF(HSMC.RX_p(10)) else ALT_IOBUF(HSMC.RX_p(10), c)
          case 28 => if(get) c := ALT_IOBUF(HSMC.TX_n(11)) else ALT_IOBUF(HSMC.TX_n(11), c)
          case 29 => if(get) c := ALT_IOBUF(HSMC.RX_n(9)) else ALT_IOBUF(HSMC.RX_n(9), c)
          case 30 => if(get) c := ALT_IOBUF(HSMC.TX_p(11)) else ALT_IOBUF(HSMC.TX_p(11), c)
          case 31 => if(get) c := ALT_IOBUF(HSMC.RX_p(9)) else ALT_IOBUF(HSMC.RX_p(9), c)
          case 32 => if(get) c := ALT_IOBUF(HSMC.TX_n(10)) else ALT_IOBUF(HSMC.TX_n(10), c)
          case 33 => if(get) c := ALT_IOBUF(HSMC.TX_n(9)) else ALT_IOBUF(HSMC.TX_n(9), c)
          case 34 => if(get) c := ALT_IOBUF(HSMC.TX_p(10)) else ALT_IOBUF(HSMC.TX_p(10), c)
          case 35 => if(get) c := ALT_IOBUF(HSMC.TX_p(9)) else ALT_IOBUF(HSMC.TX_p(9), c)
          case _ => throw new RuntimeException(s"GPIO${n}_${p} does not exist")
        }
      case 1 =>
        p match {
          case 0 => if(get) c := HSMC.CLKIN_n1 else throw new RuntimeException(s"GPIO${n}_${p} can only be input")
          case 1 => if(get) c := ALT_IOBUF(HSMC.RX_n(7)) else ALT_IOBUF(HSMC.RX_n(7), c)
          case 2 => if(get) c := HSMC.CLKIN_p1 else throw new RuntimeException(s"GPIO${n}_${p} can only be input")
          case 3 => if(get) c := ALT_IOBUF(HSMC.RX_p(7)) else ALT_IOBUF(HSMC.RX_p(7), c)
          case 4 => if(get) c := ALT_IOBUF(HSMC.TX_n(7)) else ALT_IOBUF(HSMC.TX_n(7), c)
          case 5 => if(get) c := ALT_IOBUF(HSMC.RX_n(6)) else ALT_IOBUF(HSMC.RX_n(6), c)
          case 6 => if(get) c := ALT_IOBUF(HSMC.TX_p(7)) else ALT_IOBUF(HSMC.TX_p(7), c)
          case 7 => if(get) c := ALT_IOBUF(HSMC.RX_p(6)) else ALT_IOBUF(HSMC.RX_p(6), c)
          case 8 => if(get) c := ALT_IOBUF(HSMC.TX_n(6)) else ALT_IOBUF(HSMC.TX_n(6), c)
          case 9 => if(get) c := ALT_IOBUF(HSMC.RX_n(5)) else ALT_IOBUF(HSMC.RX_n(5), c)
          case 10 => if(get) c := ALT_IOBUF(HSMC.TX_p(6)) else ALT_IOBUF(HSMC.TX_p(6), c)
          case 11 => if(get) c := ALT_IOBUF(HSMC.RX_p(5)) else ALT_IOBUF(HSMC.RX_p(5), c)
          case 12 => if(get) c := ALT_IOBUF(HSMC.TX_n(5)) else ALT_IOBUF(HSMC.TX_n(5), c)
          case 13 => if(get) c := ALT_IOBUF(HSMC.RX_n(4)) else ALT_IOBUF(HSMC.RX_n(4), c)
          case 14 => if(get) c := ALT_IOBUF(HSMC.TX_p(5)) else ALT_IOBUF(HSMC.TX_p(5), c)
          case 15 => if(get) c := ALT_IOBUF(HSMC.RX_p(4)) else ALT_IOBUF(HSMC.RX_p(4), c)
          case 16 => if(get) c := ALT_IOBUF(HSMC.OUT_n1.get) else ALT_IOBUF(HSMC.OUT_n1.get, c)
          case 17 => if(get) c := ALT_IOBUF(HSMC.RX_n(3)) else ALT_IOBUF(HSMC.RX_n(3), c)
          case 18 => if(get) c := ALT_IOBUF(HSMC.OUT_p1.get) else ALT_IOBUF(HSMC.OUT_p1.get, c)
          case 19 => if(get) c := ALT_IOBUF(HSMC.RX_p(3)) else ALT_IOBUF(HSMC.RX_p(3), c)
          case 20 => if(get) c := ALT_IOBUF(HSMC.TX_n(4)) else ALT_IOBUF(HSMC.TX_n(4), c)
          case 21 => if(get) c := ALT_IOBUF(HSMC.RX_n(2)) else ALT_IOBUF(HSMC.RX_n(2), c)
          case 22 => if(get) c := ALT_IOBUF(HSMC.TX_p(4)) else ALT_IOBUF(HSMC.TX_p(4), c)
          case 23 => if(get) c := ALT_IOBUF(HSMC.RX_p(2)) else ALT_IOBUF(HSMC.RX_p(2), c)
          case 24 => if(get) c := ALT_IOBUF(HSMC.TX_n(3)) else ALT_IOBUF(HSMC.TX_n(3), c)
          case 25 => if(get) c := ALT_IOBUF(HSMC.RX_n(1)) else ALT_IOBUF(HSMC.RX_n(1), c)
          case 26 => if(get) c := ALT_IOBUF(HSMC.TX_p(3)) else ALT_IOBUF(HSMC.TX_p(3), c)
          case 27 => if(get) c := ALT_IOBUF(HSMC.RX_p(1)) else ALT_IOBUF(HSMC.RX_p(1), c)
          case 28 => if(get) c := ALT_IOBUF(HSMC.TX_n(2)) else ALT_IOBUF(HSMC.TX_n(2), c)
          case 29 => if(get) c := ALT_IOBUF(HSMC.RX_n(0)) else ALT_IOBUF(HSMC.RX_n(0), c)
          case 30 => if(get) c := ALT_IOBUF(HSMC.TX_p(2)) else ALT_IOBUF(HSMC.TX_p(2), c)
          case 31 => if(get) c := ALT_IOBUF(HSMC.RX_p(0)) else ALT_IOBUF(HSMC.RX_p(0), c)
          case 32 => if(get) c := ALT_IOBUF(HSMC.TX_n(1)) else ALT_IOBUF(HSMC.TX_n(1), c)
          case 33 => if(get) c := ALT_IOBUF(HSMC.TX_n(0)) else ALT_IOBUF(HSMC.TX_n(0), c)
          case 34 => if(get) c := ALT_IOBUF(HSMC.TX_p(1)) else ALT_IOBUF(HSMC.TX_p(1), c)
          case 35 => if(get) c := ALT_IOBUF(HSMC.TX_p(0)) else ALT_IOBUF(HSMC.TX_p(0), c)
          case _ => throw new RuntimeException(s"GPIO${n}_${p} does not exist")
        }
      case _ => throw new RuntimeException(s"GPIO${n}_${p} does not exist")
    }
  }
}


// Based on layout of the TR4.sch done by Duy
trait WithFPGATR4ToChipConnect extends WithFPGATR4InternNoChipCreate with WithFPGATR4InternConnect {
  this: FPGATR4Shell =>

  // ******* Duy section ******
  // NOTES:
  // JP19 -> J2 / JP18 -> J3 belongs to HSMB
  // JP20 -> J2 / JP21 -> J3 belongs to HSMA
  def HSMC_JP19_18 = HSMB
  def HSMC_JP20_21 = HSMA
  def JP18 = 1 // GPIO1 (J3)
  def JP19 = 0 // GPIO0 (J2)
  def JP20 = 0 // GPIO0 (J2)
  def JP21 = 1 // GPIO1 (J3)

  // From intern = Clocks and resets
  intern.ChildClock.foreach{ a =>
    ConnectHSMCGPIO(JP21, 2, a.asBool(), false, HSMC_JP20_21)
  }
  intern.ChildReset.foreach{ a =>
    ConnectHSMCGPIO(JP18, 5, a, false, HSMC_JP19_18)
  }
  ConnectHSMCGPIO(JP21, 4, intern.sys_clk.asBool(), false, HSMC_JP20_21)
  ConnectHSMCGPIO(JP18, 2, intern.rst_n, false, HSMC_JP19_18)
  ConnectHSMCGPIO(JP18, 6, intern.jrst_n, false, HSMC_JP19_18)
  // Memory port serialized
  intern.memser.foreach{ a => a } // NOTHING
  // Ext port serialized
  intern.extser.foreach{ a => a } // NOTHING
  // Memory port
  intern.tlport.foreach{ case tlport =>
    ConnectHSMCGPIO(JP18, 1, tlport.a.valid, true, HSMC_JP19_18)
    ConnectHSMCGPIO(JP18, 4, tlport.a.ready, false, HSMC_JP19_18)
    require(tlport.a.bits.opcode.getWidth == 3, s"${tlport.a.bits.opcode.getWidth}")
    val a_opcode = Wire(Vec(3, Bool()))
    ConnectHSMCGPIO(JP18, 3, a_opcode(2), true, HSMC_JP19_18)
    ConnectHSMCGPIO(JP18, 7, a_opcode(1), true, HSMC_JP19_18)
    ConnectHSMCGPIO(JP18, 8, a_opcode(0), true, HSMC_JP19_18)
    tlport.a.bits.opcode := a_opcode.asUInt()
    require(tlport.a.bits.param.getWidth == 3, s"${tlport.a.bits.param.getWidth}")
    val a_param = Wire(Vec(3, Bool()))
    ConnectHSMCGPIO(JP18, 9, a_param(2), true, HSMC_JP19_18)
    ConnectHSMCGPIO(JP18, 10, a_param(1), true, HSMC_JP19_18)
    ConnectHSMCGPIO(JP18, 13, a_param(0), true, HSMC_JP19_18)
    tlport.a.bits.param := a_param.asUInt()
    val a_size = Wire(Vec(3, Bool()))
    require(tlport.a.bits.size.getWidth == 3, s"${tlport.a.bits.size.getWidth}")
    ConnectHSMCGPIO(JP18, 14, a_size(2), true, HSMC_JP19_18)
    ConnectHSMCGPIO(JP18, 15, a_size(1), true, HSMC_JP19_18)
    ConnectHSMCGPIO(JP18, 16, a_size(0), true, HSMC_JP19_18)
    tlport.a.bits.size := a_size.asUInt()
    require(tlport.a.bits.source.getWidth == 6, s"${tlport.a.bits.source.getWidth}")
    val a_source = Wire(Vec(6, Bool()))
    ConnectHSMCGPIO(JP18, 17, a_source(5), true, HSMC_JP19_18)
    ConnectHSMCGPIO(JP18, 18, a_source(4), true, HSMC_JP19_18)
    ConnectHSMCGPIO(JP18, 19, a_source(3), true, HSMC_JP19_18)
    ConnectHSMCGPIO(JP18, 20, a_source(2), true, HSMC_JP19_18)
    ConnectHSMCGPIO(JP18, 21, a_source(1), true, HSMC_JP19_18)
    ConnectHSMCGPIO(JP18, 22, a_source(0), true, HSMC_JP19_18)
    tlport.a.bits.source := a_source.asUInt()
    require(tlport.a.bits.address.getWidth == 32, s"${tlport.a.bits.address.getWidth}")
    val a_address = Wire(Vec(32, Bool()))
    ConnectHSMCGPIO(JP18, 23, a_address(31), true, HSMC_JP19_18)
    ConnectHSMCGPIO(JP18, 24, a_address(30), true, HSMC_JP19_18)
    ConnectHSMCGPIO(JP18, 25, a_address(29), true, HSMC_JP19_18)
    ConnectHSMCGPIO(JP18, 26, a_address(28), true, HSMC_JP19_18)
    ConnectHSMCGPIO(JP18, 27, a_address(27), true, HSMC_JP19_18)
    ConnectHSMCGPIO(JP18, 28, a_address(26), true, HSMC_JP19_18)
    ConnectHSMCGPIO(JP18, 31, a_address(25), true, HSMC_JP19_18)
    ConnectHSMCGPIO(JP18, 32, a_address(24), true, HSMC_JP19_18)
    ConnectHSMCGPIO(JP18, 33, a_address(23), true, HSMC_JP19_18)
    ConnectHSMCGPIO(JP18, 34, a_address(22), true, HSMC_JP19_18)
    ConnectHSMCGPIO(JP18, 35, a_address(21), true, HSMC_JP19_18)
    ConnectHSMCGPIO(JP18, 36, a_address(20), true, HSMC_JP19_18)
    ConnectHSMCGPIO(JP18, 37, a_address(19), true, HSMC_JP19_18)
    ConnectHSMCGPIO(JP18, 38, a_address(18), true, HSMC_JP19_18)
    ConnectHSMCGPIO(JP18, 39, a_address(17), true, HSMC_JP19_18)
    ConnectHSMCGPIO(JP18, 40, a_address(16), true, HSMC_JP19_18)
    ConnectHSMCGPIO(JP19,  1, a_address(15), true, HSMC_JP19_18)
    ConnectHSMCGPIO(JP19,  2, a_address(14), true, HSMC_JP19_18)
    ConnectHSMCGPIO(JP19,  2, a_address(13), true, HSMC_JP19_18)
    ConnectHSMCGPIO(JP19,  4, a_address(12), true, HSMC_JP19_18)
    ConnectHSMCGPIO(JP19,  5, a_address(11), true, HSMC_JP19_18)
    ConnectHSMCGPIO(JP19,  6, a_address(10), true, HSMC_JP19_18)
    ConnectHSMCGPIO(JP19,  7, a_address( 9), true, HSMC_JP19_18)
    ConnectHSMCGPIO(JP19,  8, a_address( 8), true, HSMC_JP19_18)
    ConnectHSMCGPIO(JP19,  9, a_address( 7), true, HSMC_JP19_18)
    ConnectHSMCGPIO(JP19, 10, a_address( 6), true, HSMC_JP19_18)
    ConnectHSMCGPIO(JP19, 13, a_address( 5), true, HSMC_JP19_18)
    ConnectHSMCGPIO(JP19, 14, a_address( 4), true, HSMC_JP19_18)
    ConnectHSMCGPIO(JP19, 15, a_address( 3), true, HSMC_JP19_18)
    ConnectHSMCGPIO(JP19, 16, a_address( 2), true, HSMC_JP19_18)
    ConnectHSMCGPIO(JP19, 17, a_address( 1), true, HSMC_JP19_18)
    ConnectHSMCGPIO(JP19, 18, a_address( 0), true, HSMC_JP19_18)
    tlport.a.bits.address := a_address.asUInt()
    require(tlport.a.bits.mask.getWidth == 4, s"${tlport.a.bits.mask.getWidth}")
    val a_mask = Wire(Vec(4, Bool()))
    ConnectHSMCGPIO(JP19, 19, a_mask(3), true, HSMC_JP19_18)
    ConnectHSMCGPIO(JP19, 20, a_mask(2), true, HSMC_JP19_18)
    ConnectHSMCGPIO(JP19, 21, a_mask(1), true, HSMC_JP19_18)
    ConnectHSMCGPIO(JP19, 22, a_mask(0), true, HSMC_JP19_18)
    tlport.a.bits.mask := a_mask.asUInt()
    require(tlport.a.bits.data.getWidth == 32, s"${tlport.a.bits.data.getWidth}")
    val a_data = Wire(Vec(32, Bool()))
    ConnectHSMCGPIO(JP19, 23, a_data(31), true, HSMC_JP19_18)
    ConnectHSMCGPIO(JP19, 24, a_data(30), true, HSMC_JP19_18)
    ConnectHSMCGPIO(JP19, 25, a_data(29), true, HSMC_JP19_18)
    ConnectHSMCGPIO(JP19, 26, a_data(28), true, HSMC_JP19_18)
    ConnectHSMCGPIO(JP19, 27, a_data(27), true, HSMC_JP19_18)
    ConnectHSMCGPIO(JP19, 28, a_data(26), true, HSMC_JP19_18)
    ConnectHSMCGPIO(JP19, 31, a_data(25), true, HSMC_JP19_18)
    ConnectHSMCGPIO(JP19, 32, a_data(24), true, HSMC_JP19_18)
    ConnectHSMCGPIO(JP19, 33, a_data(23), true, HSMC_JP19_18)
    ConnectHSMCGPIO(JP19, 34, a_data(22), true, HSMC_JP19_18)
    ConnectHSMCGPIO(JP19, 35, a_data(21), true, HSMC_JP19_18)
    ConnectHSMCGPIO(JP19, 36, a_data(20), true, HSMC_JP19_18)
    ConnectHSMCGPIO(JP19, 37, a_data(19), true, HSMC_JP19_18)
    ConnectHSMCGPIO(JP19, 38, a_data(18), true, HSMC_JP19_18)
    ConnectHSMCGPIO(JP19, 39, a_data(17), true, HSMC_JP19_18)
    ConnectHSMCGPIO(JP19, 40, a_data(16), true, HSMC_JP19_18)
    ConnectHSMCGPIO(JP20, 10, a_data(15), true, HSMC_JP20_21)
    ConnectHSMCGPIO(JP20,  9, a_data(14), true, HSMC_JP20_21)
    ConnectHSMCGPIO(JP20,  8, a_data(13), true, HSMC_JP20_21)
    ConnectHSMCGPIO(JP20,  7, a_data(12), true, HSMC_JP20_21)
    ConnectHSMCGPIO(JP20,  6, a_data(11), true, HSMC_JP20_21)
    ConnectHSMCGPIO(JP20,  5, a_data(10), true, HSMC_JP20_21)
    ConnectHSMCGPIO(JP20,  4, a_data( 9), true, HSMC_JP20_21)
    ConnectHSMCGPIO(JP20,  3, a_data( 8), true, HSMC_JP20_21)
    ConnectHSMCGPIO(JP20,  2, a_data( 7), true, HSMC_JP20_21)
    ConnectHSMCGPIO(JP20,  1, a_data( 6), true, HSMC_JP20_21)
    ConnectHSMCGPIO(JP20, 13, a_data( 5), true, HSMC_JP20_21)
    ConnectHSMCGPIO(JP20, 14, a_data( 4), true, HSMC_JP20_21)
    ConnectHSMCGPIO(JP20, 15, a_data( 3), true, HSMC_JP20_21)
    ConnectHSMCGPIO(JP20, 16, a_data( 2), true, HSMC_JP20_21)
    ConnectHSMCGPIO(JP20, 17, a_data( 1), true, HSMC_JP20_21)
    ConnectHSMCGPIO(JP20, 18, a_data( 0), true, HSMC_JP20_21)
    tlport.a.bits.data := a_data.asUInt()
    ConnectHSMCGPIO(JP20, 19, tlport.a.bits.corrupt, true, HSMC_JP20_21)
    ConnectHSMCGPIO(JP20, 20, tlport.d.ready, true, HSMC_JP20_21)
    ConnectHSMCGPIO(JP20, 21, tlport.d.valid, false, HSMC_JP20_21)
    require(tlport.d.bits.opcode.getWidth == 3, s"${tlport.d.bits.opcode.getWidth}")
    ConnectHSMCGPIO(JP20, 22, tlport.d.bits.opcode(2), false, HSMC_JP20_21)
    ConnectHSMCGPIO(JP20, 23, tlport.d.bits.opcode(1), false, HSMC_JP20_21)
    ConnectHSMCGPIO(JP20, 24, tlport.d.bits.opcode(0), false, HSMC_JP20_21)
    require(tlport.d.bits.param.getWidth == 2, s"${tlport.d.bits.param.getWidth}")
    ConnectHSMCGPIO(JP20, 25, tlport.d.bits.param(1), false, HSMC_JP20_21)
    ConnectHSMCGPIO(JP20, 26, tlport.d.bits.param(0), false, HSMC_JP20_21)
    require(tlport.d.bits.size.getWidth == 3, s"${tlport.d.bits.size.getWidth}")
    ConnectHSMCGPIO(JP20, 27, tlport.d.bits.size(2), false, HSMC_JP20_21)
    ConnectHSMCGPIO(JP20, 28, tlport.d.bits.size(1), false, HSMC_JP20_21)
    ConnectHSMCGPIO(JP20, 31, tlport.d.bits.size(0), false, HSMC_JP20_21)
    require(tlport.d.bits.source.getWidth == 6, s"${tlport.d.bits.source.getWidth}")
    ConnectHSMCGPIO(JP20, 32, tlport.d.bits.source(5), false, HSMC_JP20_21)
    ConnectHSMCGPIO(JP20, 33, tlport.d.bits.source(4), false, HSMC_JP20_21)
    ConnectHSMCGPIO(JP20, 34, tlport.d.bits.source(3), false, HSMC_JP20_21)
    ConnectHSMCGPIO(JP20, 35, tlport.d.bits.source(2), false, HSMC_JP20_21)
    ConnectHSMCGPIO(JP20, 36, tlport.d.bits.source(1), false, HSMC_JP20_21)
    ConnectHSMCGPIO(JP20, 37, tlport.d.bits.source(0), false, HSMC_JP20_21)
    require(tlport.d.bits.sink.getWidth == 1, s"${tlport.d.bits.sink.getWidth}")
    ConnectHSMCGPIO(JP20, 38, tlport.d.bits.sink(0), false, HSMC_JP20_21)
    ConnectHSMCGPIO(JP20, 39, tlport.d.bits.denied, false, HSMC_JP20_21)
    ConnectHSMCGPIO(JP20, 40, tlport.d.bits.corrupt, false, HSMC_JP20_21)
    require(tlport.d.bits.data.getWidth == 32, s"${tlport.d.bits.data.getWidth}")
    ConnectHSMCGPIO(JP21, 5, tlport.d.bits.data(31), false, HSMC_JP20_21)
    ConnectHSMCGPIO(JP21, 6, tlport.d.bits.data(30), false, HSMC_JP20_21)
    ConnectHSMCGPIO(JP21, 7, tlport.d.bits.data(29), false, HSMC_JP20_21)
    ConnectHSMCGPIO(JP21, 8, tlport.d.bits.data(28), false, HSMC_JP20_21)
    ConnectHSMCGPIO(JP21, 9, tlport.d.bits.data(27), false, HSMC_JP20_21)
    ConnectHSMCGPIO(JP21, 10, tlport.d.bits.data(26), false, HSMC_JP20_21)
    ConnectHSMCGPIO(JP21, 13, tlport.d.bits.data(25), false, HSMC_JP20_21)
    ConnectHSMCGPIO(JP21, 14, tlport.d.bits.data(24), false, HSMC_JP20_21)
    ConnectHSMCGPIO(JP21, 15, tlport.d.bits.data(23), false, HSMC_JP20_21)
    ConnectHSMCGPIO(JP21, 16, tlport.d.bits.data(22), false, HSMC_JP20_21)
    ConnectHSMCGPIO(JP21, 17, tlport.d.bits.data(21), false, HSMC_JP20_21)
    ConnectHSMCGPIO(JP21, 18, tlport.d.bits.data(20), false, HSMC_JP20_21)
    ConnectHSMCGPIO(JP21, 19, tlport.d.bits.data(19), false, HSMC_JP20_21)
    ConnectHSMCGPIO(JP21, 20, tlport.d.bits.data(18), false, HSMC_JP20_21)
    ConnectHSMCGPIO(JP21, 21, tlport.d.bits.data(17), false, HSMC_JP20_21)
    ConnectHSMCGPIO(JP21, 22, tlport.d.bits.data(16), false, HSMC_JP20_21)
    ConnectHSMCGPIO(JP21, 23, tlport.d.bits.data(15), false, HSMC_JP20_21)
    ConnectHSMCGPIO(JP21, 24, tlport.d.bits.data(14), false, HSMC_JP20_21)
    ConnectHSMCGPIO(JP21, 25, tlport.d.bits.data(13), false, HSMC_JP20_21)
    ConnectHSMCGPIO(JP21, 26, tlport.d.bits.data(12), false, HSMC_JP20_21)
    ConnectHSMCGPIO(JP21, 27, tlport.d.bits.data(11), false, HSMC_JP20_21)
    ConnectHSMCGPIO(JP21, 28, tlport.d.bits.data(10), false, HSMC_JP20_21)
    ConnectHSMCGPIO(JP21, 31, tlport.d.bits.data( 9), false, HSMC_JP20_21)
    ConnectHSMCGPIO(JP21, 32, tlport.d.bits.data( 8), false, HSMC_JP20_21)
    ConnectHSMCGPIO(JP21, 33, tlport.d.bits.data( 7), false, HSMC_JP20_21)
    ConnectHSMCGPIO(JP21, 34, tlport.d.bits.data( 6), false, HSMC_JP20_21)
    ConnectHSMCGPIO(JP21, 35, tlport.d.bits.data( 5), false, HSMC_JP20_21)
    ConnectHSMCGPIO(JP21, 36, tlport.d.bits.data( 4), false, HSMC_JP20_21)
    ConnectHSMCGPIO(JP21, 37, tlport.d.bits.data( 3), false, HSMC_JP20_21)
    ConnectHSMCGPIO(JP21, 38, tlport.d.bits.data( 2), false, HSMC_JP20_21)
    ConnectHSMCGPIO(JP21, 39, tlport.d.bits.data( 1), false, HSMC_JP20_21)
    ConnectHSMCGPIO(JP21, 40, tlport.d.bits.data( 0), false, HSMC_JP20_21)
  }
  
  // ******* Ahn-Dao section ******
  def HSMCSER = HSMA
  def versionSer = 1
  versionSer match {
    case _ => // TODO: There is no such thing as versions in TR4
      val MEMSER_GPIO = 0
      val EXTSER_GPIO = 1
      //ConnectHSMCGPIO(MEMSER_GPIO, 1, intern.sys_clk.asBool(), false, HSMCSER)
      //intern.ChildClock.foreach{ a => ConnectHSMCGPIO(MEMSER_GPIO, 2, a.asBool(), false, HSMCSER) }
      //intern.usbClk.foreach{ a => ConnectHSMCGPIO(MEMSER_GPIO, 3, a.asBool(), false, HSMCSER) }
      ConnectHSMCGPIO(MEMSER_GPIO, 4, intern.jrst_n, false, HSMCSER)
      ConnectHSMCGPIO(MEMSER_GPIO, 5, intern.rst_n, false, HSMCSER)
      // ExtSerMem
      intern.memser.foreach { memser =>
        val in_bits = Wire(Vec(8, Bool()))
        ConnectHSMCGPIO(MEMSER_GPIO, 9, in_bits(7), false, HSMCSER)
        ConnectHSMCGPIO(MEMSER_GPIO, 10, in_bits(6), false, HSMCSER)
        ConnectHSMCGPIO(MEMSER_GPIO, 13, in_bits(5), false, HSMCSER)
        ConnectHSMCGPIO(MEMSER_GPIO, 14, in_bits(4), false, HSMCSER)
        ConnectHSMCGPIO(MEMSER_GPIO, 15, in_bits(3), false, HSMCSER)
        ConnectHSMCGPIO(MEMSER_GPIO, 16, in_bits(2), false, HSMCSER)
        ConnectHSMCGPIO(MEMSER_GPIO, 17, in_bits(1), false, HSMCSER)
        ConnectHSMCGPIO(MEMSER_GPIO, 18, in_bits(0), false, HSMCSER)
        in_bits := memser.in.bits.asBools()
        ConnectHSMCGPIO(MEMSER_GPIO, 19, memser.in.valid, false, HSMCSER)
        ConnectHSMCGPIO(MEMSER_GPIO, 20, memser.out.ready, false, HSMCSER)
        ConnectHSMCGPIO(MEMSER_GPIO, 21, memser.in.ready, true, HSMCSER)
        val out_bits = Wire(Vec(8, Bool()))
        ConnectHSMCGPIO(MEMSER_GPIO, 22, out_bits(7), true, HSMCSER)
        ConnectHSMCGPIO(MEMSER_GPIO, 23, out_bits(6), true, HSMCSER)
        ConnectHSMCGPIO(MEMSER_GPIO, 24, out_bits(5), true, HSMCSER)
        ConnectHSMCGPIO(MEMSER_GPIO, 25, out_bits(4), true, HSMCSER)
        ConnectHSMCGPIO(MEMSER_GPIO, 26, out_bits(3), true, HSMCSER)
        ConnectHSMCGPIO(MEMSER_GPIO, 27, out_bits(2), true, HSMCSER)
        ConnectHSMCGPIO(MEMSER_GPIO, 28, out_bits(1), true, HSMCSER)
        ConnectHSMCGPIO(MEMSER_GPIO, 31, out_bits(0), true, HSMCSER)
        memser.out.bits := out_bits.asUInt()
        ConnectHSMCGPIO(MEMSER_GPIO, 32, memser.out.valid, true, HSMCSER)
      }
      // ExtSerBus
      intern.extser.foreach{ extser =>
        val in_bits = Wire(Vec(8, Bool()))
        ConnectHSMCGPIO(EXTSER_GPIO, 9, in_bits(7), false, HSMCSER)
        ConnectHSMCGPIO(EXTSER_GPIO, 10, in_bits(6), false, HSMCSER)
        ConnectHSMCGPIO(EXTSER_GPIO, 13, in_bits(5), false, HSMCSER)
        ConnectHSMCGPIO(EXTSER_GPIO, 14, in_bits(4), false, HSMCSER)
        ConnectHSMCGPIO(EXTSER_GPIO, 15, in_bits(3), false, HSMCSER)
        ConnectHSMCGPIO(EXTSER_GPIO, 16, in_bits(2), false, HSMCSER)
        ConnectHSMCGPIO(EXTSER_GPIO, 17, in_bits(1), false, HSMCSER)
        ConnectHSMCGPIO(EXTSER_GPIO, 18, in_bits(0), false, HSMCSER)
        in_bits := extser.in.bits.asBools()
        ConnectHSMCGPIO(EXTSER_GPIO, 19, extser.in.valid, false, HSMCSER)
        ConnectHSMCGPIO(EXTSER_GPIO, 20, extser.out.ready, false, HSMCSER)
        ConnectHSMCGPIO(EXTSER_GPIO, 21, extser.in.ready, true, HSMCSER)
        val out_bits = Wire(Vec(8, Bool()))
        ConnectHSMCGPIO(EXTSER_GPIO, 22, out_bits(7), true, HSMCSER)
        ConnectHSMCGPIO(EXTSER_GPIO, 23, out_bits(6), true, HSMCSER)
        ConnectHSMCGPIO(EXTSER_GPIO, 24, out_bits(5), true, HSMCSER)
        ConnectHSMCGPIO(EXTSER_GPIO, 25, out_bits(4), true, HSMCSER)
        ConnectHSMCGPIO(EXTSER_GPIO, 26, out_bits(3), true, HSMCSER)
        ConnectHSMCGPIO(EXTSER_GPIO, 27, out_bits(2), true, HSMCSER)
        ConnectHSMCGPIO(EXTSER_GPIO, 28, out_bits(1), true, HSMCSER)
        ConnectHSMCGPIO(EXTSER_GPIO, 31, out_bits(0), true, HSMCSER)
        extser.out.bits := out_bits.asUInt()
        ConnectHSMCGPIO(EXTSER_GPIO, 32, extser.out.valid, true, HSMCSER)
      }
  }

  // ******** Misc part ********

  // LEDs
  LED := Cat(
    intern.mem_status_local_cal_fail,
    intern.mem_status_local_cal_success,
    intern.mem_status_local_init_done,
    BUTTON(2)
  )
  // Clocks to the outside
  ALT_IOBUF(SMA_CLKOUT, intern.sys_clk.asBool())
  intern.ChildClock.foreach(A => ALT_IOBUF(SMA_CLKOUT_p, A.asBool()))
  intern.usbClk.foreach(A => ALT_IOBUF(SMA_CLKOUT_n, A.asBool()))
}

// Trait which connects the FPGA the chip
trait WithFPGATR4FromChipConnect extends WithFPGATR4PureConnect {
  this: FPGATR4Shell =>

  override def memEnable = false // No memory interface in this version

  // ******* Ahn-Dao section ******
  def HSMCSER = HSMA
  def versionSer = 1
  versionSer match {
    case _ => // TODO: There is no such thing as versions in TR4
      val MEMSER_GPIO = 0
      val EXTSER_GPIO = 1
      val sysclk = Wire(Bool())
      sysclk := SMA_CLKIN.asBool() //ConnectHSMCGPIO(MEMSER_GPIO, 1, sysclk,  true, HSMCSER)
      chip.sys_clk := sysclk.asClock()
      chip.ChildClock.foreach{ a =>
        val clkwire = Wire(Bool())
        clkwire := ALT_IOBUF(SMA_CLKOUT_p) // ConnectHSMCGPIO(MEMSER_GPIO, 2, clkwire,  true, HSMCSER)
        a := clkwire.asClock()
      }
      chip.usb11hs.foreach{ a =>
        val clkwire = Wire(Bool())
        clkwire := ALT_IOBUF(SMA_CLKOUT_n) // ConnectHSMCGPIO(MEMSER_GPIO, 3, clkwire,  true, HSMCSER)
        a.usbClk := clkwire.asClock()
      }
      ConnectHSMCGPIO(MEMSER_GPIO, 4, chip.jrst_n,  true, HSMCSER)
      ConnectHSMCGPIO(MEMSER_GPIO, 5, chip.rst_n,  true, HSMCSER)
      chip.aclocks.foreach{ aclocks =>
        // Only some of the aclocks are actually connected.
        println("Connecting orphan clocks =>")
        (aclocks zip namedclocks).foreach{ case (aclk, nam) =>
          println(s"  Detected clock ${nam}")
          aclk := sysclk.asClock()
        }
      }
      // ExtSerMem
      chip.memser.foreach { memser =>
        val in_bits = Wire(Vec(8, Bool()))
        ConnectHSMCGPIO(MEMSER_GPIO, 9, in_bits(7),  true, HSMCSER)
        ConnectHSMCGPIO(MEMSER_GPIO, 10, in_bits(6),  true, HSMCSER)
        ConnectHSMCGPIO(MEMSER_GPIO, 13, in_bits(5),  true, HSMCSER)
        ConnectHSMCGPIO(MEMSER_GPIO, 14, in_bits(4),  true, HSMCSER)
        ConnectHSMCGPIO(MEMSER_GPIO, 15, in_bits(3),  true, HSMCSER)
        ConnectHSMCGPIO(MEMSER_GPIO, 16, in_bits(2),  true, HSMCSER)
        ConnectHSMCGPIO(MEMSER_GPIO, 17, in_bits(1),  true, HSMCSER)
        ConnectHSMCGPIO(MEMSER_GPIO, 18, in_bits(0),  true, HSMCSER)
        memser.in.bits := in_bits.asUInt()
        ConnectHSMCGPIO(MEMSER_GPIO, 19, memser.in.valid,  true, HSMCSER)
        ConnectHSMCGPIO(MEMSER_GPIO, 20, memser.out.ready,  true, HSMCSER)
        ConnectHSMCGPIO(MEMSER_GPIO, 21, memser.in.ready,  false, HSMCSER)
        val out_bits = Wire(Vec(8, Bool()))
        ConnectHSMCGPIO(MEMSER_GPIO, 22, out_bits(7),  false, HSMCSER)
        ConnectHSMCGPIO(MEMSER_GPIO, 23, out_bits(6),  false, HSMCSER)
        ConnectHSMCGPIO(MEMSER_GPIO, 24, out_bits(5),  false, HSMCSER)
        ConnectHSMCGPIO(MEMSER_GPIO, 25, out_bits(4),  false, HSMCSER)
        ConnectHSMCGPIO(MEMSER_GPIO, 26, out_bits(3),  false, HSMCSER)
        ConnectHSMCGPIO(MEMSER_GPIO, 27, out_bits(2),  false, HSMCSER)
        ConnectHSMCGPIO(MEMSER_GPIO, 28, out_bits(1),  false, HSMCSER)
        ConnectHSMCGPIO(MEMSER_GPIO, 31, out_bits(0),  false, HSMCSER)
        out_bits := memser.out.bits.asBools()
        ConnectHSMCGPIO(MEMSER_GPIO, 32, memser.out.valid,  false, HSMCSER)
      }
      // ExtSerBus
      chip.extser.foreach{ extser =>
        val in_bits = Wire(Vec(8, Bool()))
        ConnectHSMCGPIO(EXTSER_GPIO, 9, in_bits(7),  true, HSMCSER)
        ConnectHSMCGPIO(EXTSER_GPIO, 10, in_bits(6),  true, HSMCSER)
        ConnectHSMCGPIO(EXTSER_GPIO, 13, in_bits(5),  true, HSMCSER)
        ConnectHSMCGPIO(EXTSER_GPIO, 14, in_bits(4),  true, HSMCSER)
        ConnectHSMCGPIO(EXTSER_GPIO, 15, in_bits(3),  true, HSMCSER)
        ConnectHSMCGPIO(EXTSER_GPIO, 16, in_bits(2),  true, HSMCSER)
        ConnectHSMCGPIO(EXTSER_GPIO, 17, in_bits(1),  true, HSMCSER)
        ConnectHSMCGPIO(EXTSER_GPIO, 18, in_bits(0),  true, HSMCSER)
        extser.in.bits := in_bits.asUInt()
        ConnectHSMCGPIO(EXTSER_GPIO, 19, extser.in.valid,  true, HSMCSER)
        ConnectHSMCGPIO(EXTSER_GPIO, 20, extser.out.ready,  true, HSMCSER)
        ConnectHSMCGPIO(EXTSER_GPIO, 21, extser.in.ready,  false, HSMCSER)
        val out_bits = Wire(Vec(8, Bool()))
        ConnectHSMCGPIO(EXTSER_GPIO, 22, out_bits(7),  false, HSMCSER)
        ConnectHSMCGPIO(EXTSER_GPIO, 23, out_bits(6),  false, HSMCSER)
        ConnectHSMCGPIO(EXTSER_GPIO, 24, out_bits(5),  false, HSMCSER)
        ConnectHSMCGPIO(EXTSER_GPIO, 25, out_bits(4),  false, HSMCSER)
        ConnectHSMCGPIO(EXTSER_GPIO, 26, out_bits(3),  false, HSMCSER)
        ConnectHSMCGPIO(EXTSER_GPIO, 27, out_bits(2),  false, HSMCSER)
        ConnectHSMCGPIO(EXTSER_GPIO, 28, out_bits(1),  false, HSMCSER)
        ConnectHSMCGPIO(EXTSER_GPIO, 31, out_bits(0),  false, HSMCSER)
        out_bits := extser.out.bits.asBools()
        ConnectHSMCGPIO(EXTSER_GPIO, 32, extser.out.valid,  false, HSMCSER)
      }
  }
}