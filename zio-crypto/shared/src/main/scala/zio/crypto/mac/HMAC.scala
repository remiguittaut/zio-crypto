package zio.crypto.mac

import com.google.crypto.tink.mac.{HmacKeyManager, MacConfig}
import com.google.crypto.tink.{Mac, KeyTemplate => TinkKeyTemplate}
import zio._
import zio.crypto.ByteHelpers
import zio.crypto.keyset.{KeyTemplate, ZKeysetHandle}

import java.nio.charset.Charset
import scala.util.Try

sealed trait HMACAlgorithm

object HMACAlgorithm {
  case object HMACSHA256           extends HMACAlgorithm
  case object HMACSHA256HalfDigest extends HMACAlgorithm
  case object HMACSHA512           extends HMACAlgorithm
  case object HMACSHA512HalfDigest extends HMACAlgorithm

  implicit val template: KeyTemplate[HMACAlgorithm] = new KeyTemplate[HMACAlgorithm] {
    override def templateURL: String = "type.googleapis.com/google.crypto.tink.HmacKey"

    override def getTinkKeyTemplate(a: HMACAlgorithm): TinkKeyTemplate =
      a match {
        case HMACAlgorithm.HMACSHA256           => HmacKeyManager.hmacSha256Template()
        case HMACAlgorithm.HMACSHA256HalfDigest => HmacKeyManager.hmacSha256HalfDigestTemplate()
        case HMACAlgorithm.HMACSHA512           => HmacKeyManager.hmacSha512Template()
        case HMACAlgorithm.HMACSHA512HalfDigest => HmacKeyManager.hmacSha512HalfDigestTemplate()
      }
  }
}

final case class HMACObject[Kind](value: Kind) extends AnyVal

object HMAC {

  type HMAC = Has[HMAC.Service]

  trait Service {
    def sign(m: Chunk[Byte], k: ZKeysetHandle[HMACAlgorithm]): HMACObject[Chunk[Byte]]
    def verify(m: Chunk[Byte], hmac: HMACObject[Chunk[Byte]], k: ZKeysetHandle[HMACAlgorithm]): Boolean

    def sign(m: String, k: ZKeysetHandle[HMACAlgorithm], charset: Charset): HMACObject[String]
    def verify(m: String, hmac: HMACObject[String], k: ZKeysetHandle[HMACAlgorithm], charset: Charset): Boolean
  }

  val live: TaskLayer[HMAC] = Task
    .effect(MacConfig.register())
    .as {
      new Service {
        override def sign(m: Chunk[Byte], k: ZKeysetHandle[HMACAlgorithm]): HMACObject[Chunk[Byte]] =
          HMACObject(
            Chunk.fromArray(
              k.keysetHandle.getPrimitive(classOf[Mac]).computeMac(m.toArray)
            )
          )

        override def verify(
          m: Chunk[Byte],
          hmac: HMACObject[Chunk[Byte]],
          k: ZKeysetHandle[HMACAlgorithm]
        ): Boolean =
          Try(k.keysetHandle.getPrimitive(classOf[Mac]).verifyMac(hmac.value.toArray, m.toArray))
            .map(_ => true)
            .toOption
            .getOrElse(false)

        override def sign(m: String, k: ZKeysetHandle[HMACAlgorithm], charset: Charset): HMACObject[String] =
          HMACObject(
            ByteHelpers.toB64String(
              sign(
                m = Chunk.fromArray(
                  // May throw CharacterCodingException
                  m.getBytes(charset)
                ),
                k = k
              ).value
            )
          )

        override def verify(m: String, hmac: HMACObject[String], k: ZKeysetHandle[HMACAlgorithm], charset: Charset)
          : Boolean =
          ByteHelpers
            .fromB64String(hmac.value)
            .exists(d =>
              verify(
                m = Chunk.fromArray(
                  // May throw CharacterCodingException
                  m.getBytes(charset)
                ),
                HMACObject(d),
                k = k
              )
            )

      }
    }
    .toLayer

  /**
   * Computes the HMAC of a message `m` with the key `k`.
   *
   * @param m: the message to sign
   * @param k: the secret key to use for signing
   * @return the HMAC of `m`
   */
  def sign(m: Chunk[Byte], k: ZKeysetHandle[HMACAlgorithm]): RIO[HMAC, HMACObject[Chunk[Byte]]] =
    ZIO.access(_.get.sign(m, k))

  /**
   * Verifies that `hmac` is a valid message authentication code for `m`.
   *
   * @param m: the message to check.
   * @param hmac: the `HMAC` object to verify against.
   * @param k: the secret key used for signing.
   * @return true if `hmac` is a valid HMAC for `m` under `k`, and false otherwise.
   */
  def verify(m: Chunk[Byte], hmac: HMACObject[Chunk[Byte]], k: ZKeysetHandle[HMACAlgorithm]): RIO[HMAC, Boolean] =
    ZIO.access(_.get.verify(m, hmac, k))

  /**
   * Computes the HMAC of a message `m` with the key `k`.
   *
   * @param m: the message to sign, encoded with `charset`.
   * @param k: the secret key to use for signing.
   * @param charset: the `Charset` of `m`.
   * @return the HMAC of `m`
   */
  def sign(m: String, k: ZKeysetHandle[HMACAlgorithm], charset: Charset): RIO[HMAC, HMACObject[String]] =
    ZIO.access(_.get.sign(m, k, charset))

  /**
   * Verifies that `hmac` is a valid message authentication code for `m`.
   *
   * @param m: the message to check.
   * @param hmac: the `HMAC` object to verify against.
   * @param k: the secret key used for signing.
   * @param charset: the `Charset` of `m`.
   * @return true if `hmac` is a valid HMAC for `m` under `k`, and false otherwise.
   */
  def verify(
    m: String,
    hmac: HMACObject[String],
    k: ZKeysetHandle[HMACAlgorithm],
    charset: Charset
  ): RIO[HMAC, Boolean] =
    ZIO.access(_.get.verify(m, hmac, k, charset))

}