package io.lumnik.hub.fidelity;

/** The splitmix64 PRNG shared by the OTK generators — pure integer math, stable across JVMs and
 *  toolchains so the seed -> scenario mapping is reproducible. Mirrors the lm Go OTK. */
final class OtkRng {

  private OtkRng() {}

  static long mix(long x) {
    x += 0x9e3779b97f4a7c15L;
    x = (x ^ (x >>> 30)) * 0xbf58476d1ce4e5b9L;
    x = (x ^ (x >>> 27)) * 0x94d049bb133111ebL;
    return x ^ (x >>> 31);
  }
}
