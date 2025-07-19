package parallax.utilities

object Misc {
  def isPow2(n: Int): Boolean = (n > 0) && ((n & (n - 1)) == 0)
}
