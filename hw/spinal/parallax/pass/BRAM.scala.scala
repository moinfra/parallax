package parallax.pass

import spinal.core.internals._


class EnforceSyncRamPhase extends PhaseMemBlackboxing{
  override def doBlackboxing(pc: PhaseContext, typo: MemTopology) = {
    if(typo.readsAsync.size == 0){
      typo.mem.addAttribute("ram_style", "block")
    }
  }
}
