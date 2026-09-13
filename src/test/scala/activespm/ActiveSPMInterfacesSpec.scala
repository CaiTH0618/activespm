package activespm

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class ActiveSPMInterfacesSpec extends AnyFlatSpec with Matchers {
  "ActiveSPM interface encodings" should "match the software-visible direction ABI" in {
    ActiveSPMDirection.load.litValue shouldBe 0
    ActiveSPMDirection.store.litValue shouldBe 1
  }

  it should "match the software-visible error ABI" in {
    ActiveSPMErrorCode.none.litValue shouldBe 0
    ActiveSPMErrorCode.busy.litValue shouldBe 1
    ActiveSPMErrorCode.localRange.litValue shouldBe 2
    ActiveSPMErrorCode.addressOverflow.litValue shouldBe 3
    ActiveSPMErrorCode.externalRange.litValue shouldBe 4
    ActiveSPMErrorCode.tileLink.litValue shouldBe 5
  }

  it should "keep the documented register layout" in {
    Seq(
      ActiveSPMRegisters.commandOffset,
      ActiveSPMRegisters.externalAddressOffset,
      ActiveSPMRegisters.localOffsetOffset,
      ActiveSPMRegisters.byteCountOffset,
      ActiveSPMRegisters.statusOffset,
      ActiveSPMRegisters.bytesCompletedOffset,
      ActiveSPMRegisters.errorCodeOffset) shouldBe Seq(0x00, 0x08, 0x10, 0x18, 0x20, 0x28, 0x30)
    ActiveSPMRegisters.accessBytes shouldBe 8
  }

  it should "keep request, completion, and progress field names and widths stable" in {
    val request = new ActiveSPMDMARequest
    request.elements.keys.toSeq shouldBe Seq("direction", "externalAddress", "localOffset", "byteCount")
    request.elements("direction").getWidth shouldBe 1
    request.externalAddress.getWidth shouldBe 64
    request.localOffset.getWidth shouldBe 64
    request.byteCount.getWidth shouldBe 64

    val completion = new ActiveSPMDMACompletion
    completion.errorCode.getWidth shouldBe 3
    completion.bytesCompleted.getWidth shouldBe 64

    val progress = new ActiveSPMDMAProgress
    progress.busy.getWidth shouldBe 1
    progress.bytesCompleted.getWidth shouldBe 64
  }
}
