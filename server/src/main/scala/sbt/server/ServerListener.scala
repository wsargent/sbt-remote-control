package sbt
package server

import concurrent.Future
import sbt.protocol.{ ScopedKey => _, _ }

trait MessageSink[-J <: Message] {
  /** Sends a message out.  This should be a safe call (doens't throw on bad client for example.) */
  def send(msg: J): Unit
}

/**
 * An interface we can use to send messages to an sbt client.
 *
 * TODO - better name!
 */
sealed trait SbtClient extends MessageSink[Message] {
  /** Creates a new client that will send events to *both* of these clients. */
  def zip(other: SbtClient): SbtClient = (this, other) match {
    case (NullSbtClient, NullSbtClient) => NullSbtClient
    case (NullSbtClient, client) => client
    case (client, NullSbtClient) => client
    case (JoinedSbtClient(clients), JoinedSbtClient(clients2)) => JoinedSbtClient(clients ++ clients2)
    case (JoinedSbtClient(clients), other) => JoinedSbtClient(clients + other)
    case (other, JoinedSbtClient(clients2)) => JoinedSbtClient(clients2 + other)
    case (other, other2) => JoinedSbtClient(Set(other, other2))
  }
  // Removes a particular client from this potential aggregate client.
  def without(client: SbtClient): SbtClient =
    this match {
      case `client` | NullSbtClient => NullSbtClient
      case JoinedSbtClient(clients) if clients.contains(client) =>
        JoinedSbtClient(clients filterNot (_ == client))
      case other => other
    }
}

object NullSbtClient extends SbtClient {
  override final def send(msg: Message): Unit = ()
  override def toString = "NullSbtClient"
}
final case class JoinedSbtClient(clients: Set[SbtClient]) extends SbtClient {
  // TODO - ignore individual failures?
  override final def send(msg: Message): Unit =
    clients foreach (_ send msg)
  override def toString = clients.mkString("Joined(", ",", ")")
}

// This is what concrete implementations implement.
abstract class LiveClient extends SbtClient {
  def uuid: java.util.UUID
  def configName: String
  def humanReadableName: String
  def protocolVersion: ProtocolVersion
  def featureTags: Seq[FeatureTag]

  @volatile
  var daemon: Boolean = false

  def info: protocol.ClientInfo =
    protocol.ClientInfo(uuid = uuid.toString, configName = configName, humanReadableName = humanReadableName, protocolVersion = protocolVersion, featureTags = featureTags.toVector)

  /** requests a line of input from the client.  This will return sometime in the future. */
  def readLine(executionId: ExecutionId, prompt: String, mask: Boolean): Future[Option[String]]
  /** Confirms a message from a client. */
  def confirm(executionId: ExecutionId, msg: String): Future[Boolean]
  def reply(replyTo: Long, msg: Response): Unit
}

final case class KeyValueClientListener[T](
  key: ScopedKey[T],
  client: SbtClient) {
  /** Disconnect a client from this listener. */
  def remove(c: SbtClient): KeyValueClientListener[T] =
    copy(client = client without c)

  def add(c: SbtClient): KeyValueClientListener[T] =
    copy(client = client zip c)
}

