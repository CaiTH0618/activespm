package activespm

import freechips.rocketchip.diplomacy.AddressSet
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class ActiveSPMParametersSpec extends AnyFlatSpec with Matchers {
  private def params(
    id: Int = 0,
    controlBase: BigInt = 0x10050000L,
    scratchpadBase: BigInt = 0x70000000L,
    spadBeatBytes: Int = 8,
    nBanks: Int = 4,
    externalRanges: Seq[AddressSet] = Seq(AddressSet(0x80000000L, 0x0fffffffL)),
    controlMask: BigInt = 0xfffL,
    scratchpadMask: BigInt = 0xffffL
  ): ActiveSPMParams = ActiveSPMParams(
    id = id,
    controlAddress = AddressSet(controlBase, controlMask),
    scratchpadAddress = AddressSet(scratchpadBase, scratchpadMask),
    spadBeatBytes = spadBeatBytes,
    nBanks = nBanks,
    externalMemoryRanges = externalRanges)

  "ActiveSPMParams" should "accept a valid instance and derive its public names" in {
    val instance = params(id = 3)
    instance.scratchpadSize shouldBe 0x10000L
    instance.controlNodeName shouldBe "activespm-ctrl[3]"
    instance.scratchpadNodeName shouldBe "activespm-spad[3]"
    instance.dmaNodeName shouldBe "activespm-dma[3]"
  }

  it should "derive disjoint beat-interleaved bank address sets" in {
    Seq(1, 2, 4, 8).foreach { nBanks =>
      val instance = params(nBanks = nBanks)
      instance.bankAddressSets should have size nBanks
      instance.bankAddressSets.combinations(2).foreach {
        case Seq(left, right) => left.overlaps(right) shouldBe false
        case _ => fail("unexpected bank-address combination")
      }
      instance.bankAddressSets.foreach { bank =>
        (BigInt(1) << bank.mask.bitCount) shouldBe instance.scratchpadSize / nBanks
      }
    }

    val instance = params(nBanks = 4)
    Seq(0x00L, 0x08L, 0x10L, 0x18L, 0x20L).map { offset =>
      instance.bankAddressSets.indexWhere(_.contains(instance.scratchpadAddress.base + offset))
    } shouldBe Seq(0, 1, 2, 3, 0)
  }

  it should "reject invalid scalar and range parameters" in {
    an[IllegalArgumentException] should be thrownBy params(id = -1)
    an[IllegalArgumentException] should be thrownBy params(spadBeatBytes = 6)
    an[IllegalArgumentException] should be thrownBy params(nBanks = 3)
    an[IllegalArgumentException] should be thrownBy params(nBanks = 16384)
    an[IllegalArgumentException] should be thrownBy params(externalRanges = Nil)
    an[IllegalArgumentException] should be thrownBy params(controlBase = 0x10050004L, controlMask = 0x3L)
    an[IllegalArgumentException] should be thrownBy params(controlMask = 0x1fL)
    an[IllegalArgumentException] should be thrownBy params(controlMask = 0x17ffL)
    an[IllegalArgumentException] should be thrownBy params(scratchpadMask = 0x17fffL)
    an[IllegalArgumentException] should be thrownBy params(
      externalRanges = Seq(AddressSet(0x80000000L, 0x17ffL)))
  }

  it should "accept distinct non-overlapping instances" in {
    noException should be thrownBy ActiveSPMParams.validateInstances(Seq(
      params(id = 0),
      params(id = 1, controlBase = 0x10051000L, scratchpadBase = 0x70010000L)))
  }

  it should "reject duplicate IDs and overlapping public ranges" in {
    an[IllegalArgumentException] should be thrownBy ActiveSPMParams.validateInstances(Seq(
      params(id = 0),
      params(id = 0, controlBase = 0x10051000L, scratchpadBase = 0x70010000L)))

    an[IllegalArgumentException] should be thrownBy ActiveSPMParams.validateInstances(Seq(
      params(id = 0),
      params(id = 1, controlBase = 0x10050000L, scratchpadBase = 0x70010000L)))

    an[IllegalArgumentException] should be thrownBy ActiveSPMParams.validateInstances(Seq(
      params(id = 0),
      params(id = 1, controlBase = 0x10051000L, scratchpadBase = 0x70000000L)))
  }
}
