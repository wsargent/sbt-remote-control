package sbt
package client

import java.io.Closeable
import concurrent.{ ExecutionContext, Future }
import sbt.serialization._
import sbt.protocol.{ Message, Response, ProtocolVersion, FeatureTag }

final class ChannelInUseException() extends Exception("This channel is already in use and can only be claimed once")

/**
 * SbtChannel is a "raw" connection to the sbt server which gives you the plain
 *  protocol without keeping track of or caching anything for you. Wrap it
 *  in SbtClient for a much more convenient API.
 *
 * Note: this trait will add methods over time, which will be ABI-compatible but not source compatible
 * if you subtype it. Don't extend this trait if you can't live with that.
 */
trait SbtChannel extends Closeable {
  /** UUID of this sbt connection, different every time we connect. */
  def uuid: java.util.UUID
  /**
   * Name used to store configuration associated with this connection; usually
   *  the same machine-readable name every time the same app connects.
   */
  def configName: String
  /** Human-readable name of this client, such as the name of the app. */
  def humanReadableName: String

  /** version of protocol supported by server */
  def serverProtocolVersion: ProtocolVersion

  /** protocol feature tags exported by server */
  def serverTags: Seq[FeatureTag]

  /**
   * Send a message over the sbt socket.
   *  If we fail to write to the socket, the future gets an exception. Note that just because
   *  the future succeeds doesn't mean the server received and acted on the message.
   */
  def sendMessage(message: Message): Future[Unit]

  /**
   * Send a message over the sbt socket, getting the serial in a callback which allows you to
   *  provide a result based on the reply. The "registration" callback is run synchronously
   *  (before this method returns) and will always run before the message is sent.
   */
  def sendMessageWithRegistration[R](message: Message)(registration: Long => Future[R]): Future[R]

  // TODO remove type parameter if we don't add any implicits
  /**
   * Send a reply message (replyTo is the serial of the request we are replying to;
   * each request gets 0 or 1 replies, defined in the protocol for each kind of request.
   * If we fail to write to the socket, the future gets an exception. Note that just because
   * the future succeeds doesn't mean the server received and acted on the message.
   */
  def replyMessage(replyTo: Long, message: Response): Future[Unit]

  /**
   * Invoke a function in the given ExecutionContext for every message received over this channel.
   * This may be called ONLY ONCE by whoever will primarily use the channel; calling it claims the channel
   * and starts handling events. This avoids races on startup (we don't want to lose events before
   * a handler has been attached).
   * If this is called twice you will get ChannelInUseException. All channels need a "primary
   * owner" which controls when the stream of events starts and handles requests and such.
   *
   * The listener is guaranteed to get a ClosedEvent as the last message; if the channel is
   * already closed, it will be sent immediately (through the provided ExecutionContext).
   *
   * NOTE your ExecutionContext needs to keep messages in order or you will be sad!
   */
  def claimMessages(listener: protocol.Envelope => Unit)(implicit ex: ExecutionContext): Subscription

  /**
   * Like claimMessages but can be called more than once and does not start the message stream. No messages will be sent
   *  until someone does claimMessages().
   */
  def handleMessages(listener: protocol.Envelope => Unit)(implicit ex: ExecutionContext): Subscription

  /** true if close() has been called or the socket was closed by the server. */
  def isClosed: Boolean
}
