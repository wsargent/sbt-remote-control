package sbt
package client

import sbt.protocol._
import sbt.serialization._
import java.io.Closeable
import scala.concurrent.{ ExecutionContext, Future }

/**
 * This is the high-level interface for talking to an sbt server; use SbtChannel for the low-level one.
 *  This high-level interface tracks a lot of state on the client side and provides convenience methods
 *  to shield you from using the raw protocol.
 *
 * Note: this trait will add methods over time, which will be ABI-compatible but not source compatible
 * if you subtype it. Don't extend this trait if you can't live with that.
 */
trait SbtClient extends Closeable {

  def channel: SbtChannel

  def uuid: java.util.UUID
  def configName: String
  def humanReadableName: String

  /** version of protocol supported by server */
  def serverProtocolVersion: ProtocolVersion

  /** protocol feature tags exported by server */
  def serverTags: Seq[FeatureTag]

  /**
   * Set whether the client keeps the sbt server alive. Daemon clients do not prevent
   * the server from exiting (it exits after a timeout). Typically, user-visible tools
   * should not be daemon clients.
   * @param daemon true if the server should feel free to exit with us connected
   * @returns a future which is completed if the request is ack'd by the server
   */
  def setDaemon(daemon: Boolean): Future[Unit]

  /**
   * Watch the build structure, receiving notification when it changes.
   * When initially calling watchBuild(), at least one initial notification
   * is guaranteed to be sent (asynchronously) with the latest build structure.
   *
   * @param listener listener that is notified on build structure changes.
   * @param ex The context in which we should execute the listener.
   *
   * @return
   *      A subscription which can be used to unsubscribe to notifications.
   *
   * @note To preserve ordering of notifications, use the same single-threaded ExecutionContext
   *       for all listeners on the same SbtClient.
   */
  def watchBuild(listener: BuildStructureListener)(implicit ex: ExecutionContext): Subscription

  /**
   * like watchBuild() but it does NOT guarantee an initial notification; we will only be
   * notified if the build structure actually changes.
   *
   * @param listener listener that is notified on build structure changes.
   * @param ex The context in which we should execute the listener.
   *
   * @return
   *      A subscription which can be used to unsubscribe to notifications.
   *
   * @note To preserve ordering of notifications, use the same single-threaded ExecutionContext
   *       for all listeners on the same SbtClient.
   */
  def lazyWatchBuild(listener: BuildStructureListener)(implicit ex: ExecutionContext): Subscription

  // TODO - A mechanism to dynamically load key-dependencies, which are too expensive to compute up front.
  // Possibly via watching a project and pulling configuration off of it.

  /**
   * Gives us the autocompletions possible for a given command string.
   *
   * @param partialCommand  An incomplete command or task string
   *
   * @return A set of "completion" strings that could be used as sbt commands.  These
   *         completions are triples of:
   *         - the string to append
   *         - the string to display to users
   *         - a flag denoting if the completion is empty.
   */
  def possibleAutocompletions(partialCommand: String, detailLevel: Int): Future[Vector[Completion]]

  /**
   * This tries to find whether there is a build key associated with the
   * current string.  Returns all such keys (if aggregation is enabled).
   * sbt would generally run ALL of the returned keys if you requestExecution(name).
   */
  def lookupScopedKey(name: String): Future[Seq[ScopedKey]]

  /**
   * List all settings.
   */
  def listSettings(): Future[Seq[ScopedKey]]

  /**
   * This looks up details about the setting or task associated
   * with the given key.
   */
  def inspectKey(key: ScopedKey, preanalyze: Boolean): Future[InspectResponse]

  /**
   * This analyzes how sbt will interpret "command" if you do requestExecution(command).
   * It tells you whether the given string can be executed and whether it will be
   * a task or a command.
   */
  def analyzeExecution(command: String): Future[ExecutionAnalysis]

  /**
   * Asks to run the command/task associated with the given input string.
   * The server has a queue of input strings to execute. When a string is
   * added to the queue, it gets an execution ID (returned from this method)
   * which will appear in related events. Events include ExecutionWaiting
   * when the execution is queued, ExecutionStarting when it is about to
   * run, and either ExecutionSuccess or ExecutionFailure when it is complete.
   *
   * Duplicates in the queue are combined. This means that if you requestExecution()
   * and the given command or task is already in the queue, you will get an
   * existing execution ID back, and there will not be a new ExecutionWaiting
   * event. Another implication of this is that execution requests may appear
   * to be re-ordered (since you can "jump ahead" in the queue if your request
   * is combined with one which is already present).
   *
   * @param commandOrTask The full command/task string to run.
   *         that should be evaluated.
   * @return A future execution ID, which then appears in execution-related events
   */
  def requestExecution(commandOrTask: String, interaction: Option[(Interaction, ExecutionContext)]): Future[Long]

  /**
   * See the docs for the other variant of requestExecution(). This one takes a key rather than
   * a string.
   * @param key key for the task to run
   * @return A future execution ID, which then appears in execution-related events
   */
  def requestExecution(key: ScopedKey, interaction: Option[(Interaction, ExecutionContext)]): Future[Long]

  /**
   * Attempts to cancel the exeuction of some command/task.
   *
   * @param The execution ID we want to cancel
   *
   * @return  A future that is either true/false,
   *           depending on the assumed sucess of cancelling the task.
   *          True means the task was able to receive a cancel notificatoin.
   *          False means the task was already finished *or* already cancelled.
   */
  def cancelExecution(id: Long): Future[Boolean]

  /**
   * Adds a listener to general events which are fired from this sbt server.  These can be things like
   *  "TaskStarted, TaskCanceled, or even custom events from plugins (via GenericEvent).
   *
   *  @param listener  A function that is called when events are fired from sbt.
   *  @param ex        The execution context on which to call the listener.
   *  @note To preserve ordering of notifications, use the same single-threaded ExecutionContext
   *        for all listeners on the same SbtClient.
   */
  def handleEvents(listener: EventListener)(implicit ex: ExecutionContext): Subscription
  /**
   * Adds a listener to a particular setting.  If this setting changes, the event listener
   *  will be notified with the new value. In addition, the current value of the
   *  setting will immediately (asynchronously) be sent to this listener.
   *
   *  @param key  The setting to listen to changes on.
   *  @param listener  A function that is called when the setting value changes.
   *  @param ex        The execution context on which to call the listener.
   *
   *  @note To preserve ordering of notifications, use the same single-threaded ExecutionContext
   *        for all listeners on the same SbtClient.
   */
  def rawWatch(key: SettingKey[_])(listener: RawValueListener)(implicit ex: ExecutionContext): Subscription
  /**
   * Adds a listener to a particular setting as with rawWatch(), but does not receive immediate
   * notification of the current setting value.
   *
   *  @param key  The setting to listen to changes on.
   *  @param listener  A function that is called when the setting value changes.
   *  @param ex        The execution context on which to call the listener.
   *
   *  @note To preserve ordering of notifications, use the same single-threaded ExecutionContext
   *        for all listeners on the same SbtClient.
   */
  def rawLazyWatch(key: SettingKey[_])(listener: RawValueListener)(implicit ex: ExecutionContext): Subscription
  /**
   * Adds a listener for the value of a particular task.  If the evaluated task result changes, the event
   *  listener will be notified of the new value. In addition, the task will be evaluated IMMEDIATELY
   *  and the listener asynchronously notified of the task's latest value.
   *
   *  Since tasks read their state from the filesystem, it is not guaranteed that an event will be fired if
   *  filesystem changes mean that a task _would_ change if we were to run it. Watching a task just means
   *  that _when_ it runs, we are notified of new results; it does not mean that we auto-run it
   *  as the filesystem changes.
   *
   *  @param key       The task to listen to changes for.
   *  @param listener  A function that is called when the setting value changes.
   *  @param ex        The execution context on which to call the listener.
   *
   * @note To preserve ordering of notifications, use the same single-threaded ExecutionContext
   *       for all listeners on the same SbtClient.
   */
  def rawWatch(key: TaskKey[_])(l: RawValueListener)(implicit ex: ExecutionContext): Subscription
  /**
   * Like rawWatch() except that it does NOT kick off an immediate task evaluation; it just listens
   * to any new values that result if the task is evaluated in the future.
   *
   *  @param key       The task to listen to changes for.
   *  @param listener  A function that is called when the setting value changes.
   *  @param ex        The execution context on which to call the listener.
   *
   *  @note To preserve ordering of notifications, use the same single-threaded ExecutionContext
   *        for all listeners on the same SbtClient.
   */
  def rawLazyWatch(key: TaskKey[_])(l: RawValueListener)(implicit ex: ExecutionContext): Subscription

  /**
   * Adds a listener to a particular setting.  If this setting changes, the event listener
   *  will be notified with the new value. In addition, the current value of the
   *  setting will immediately (asynchronously) be sent to this listener.
   *
   *  @param key  The setting to listen to changes on.
   *  @param listener  A function that is called when the setting value changes.
   *  @param ex        The execution context on which to call the listener.
   *
   *  @note To preserve ordering of notifications, use the same single-threaded ExecutionContext
   *        for all listeners on the same SbtClient.
   */
  def watch[T](key: SettingKey[T])(listener: ValueListener[T])(implicit unpickler: Unpickler[T], ex: ExecutionContext): Subscription
  /**
   * Adds a listener to a particular setting as with watch(), but does not receive immediate
   * notification of the current setting value.
   *
   *  @param key  The setting to listen to changes on.
   *  @param listener  A function that is called when the setting value changes.
   *  @param ex        The execution context on which to call the listener.
   *
   *  @note To preserve ordering of notifications, use the same single-threaded ExecutionContext
   *        for all listeners on the same SbtClient.
   */
  def lazyWatch[T](key: SettingKey[T])(listener: ValueListener[T])(implicit unpickler: Unpickler[T], ex: ExecutionContext): Subscription
  /**
   * Adds a listener for the value of a particular task.  If the evaluated task result changes, the event
   *  listener will be notified of the new value. In addition, the task will be evaluated IMMEDIATELY
   *  and the listener asynchronously notified of the task's latest value.
   *
   *  Since tasks read their state from the filesystem, it is not guaranteed that an event will be fired if
   *  filesystem changes mean that a task _would_ change if we were to run it. Watching a task just means
   *  that _when_ it runs, we are notified of new results; it does not mean that we auto-run it
   *  as the filesystem changes.
   *
   *  @param key       The task to listen to changes for.
   *  @param listener  A function that is called when the setting value changes.
   *  @param ex        The execution context on which to call the listener.
   *
   * @note To preserve ordering of notifications, use the same single-threaded ExecutionContext
   *       for all listeners on the same SbtClient.
   */
  def watch[T](key: TaskKey[T])(l: ValueListener[T])(implicit unpickler: Unpickler[T], ex: ExecutionContext): Subscription
  /**
   * Like watch() except that it does NOT kick off an immediate task evaluation; it just listens
   * to any new values that result if the task is evaluated in the future.
   *
   *  @param key       The task to listen to changes for.
   *  @param listener  A function that is called when the setting value changes.
   *  @param ex        The execution context on which to call the listener.
   *
   *  @note To preserve ordering of notifications, use the same single-threaded ExecutionContext
   *        for all listeners on the same SbtClient.
   */
  def lazyWatch[T](key: TaskKey[T])(l: ValueListener[T])(implicit unpickler: Unpickler[T], ex: ExecutionContext): Subscription
  /**
   *  Looks up the string to obtain scoped keys, if any, matching it; then sets up watches
   *  for each matching task or setting key. If no keys match, still calls the value listener
   *  one time with an error.
   *  @param name       Name to look up keys for and then watch those keys.
   *  @param listener  A function that is called when the setting value changes.
   *  @param ex        The execution context on which to call the listener.
   *
   * @note To preserve ordering of notifications, use the same single-threaded ExecutionContext
   *       for all listeners on the same SbtClient.
   */
  def watch[T](name: String)(l: ValueListener[T])(implicit unpickler: Unpickler[T], ex: ExecutionContext): Subscription
  /**
   * Like watch(String) except that it calls lazyWatch() for each discovered key instead of watch().
   *
   *  @param name       Name to look up keys for and then watch those keys.
   *  @param listener  A function that is called when the setting value changes.
   *  @param ex        The execution context on which to call the listener.
   *
   *  @note To preserve ordering of notifications, use the same single-threaded ExecutionContext
   *        for all listeners on the same SbtClient.
   */
  def lazyWatch[T](name: String)(l: ValueListener[T])(implicit unpickler: Unpickler[T], ex: ExecutionContext): Subscription
  /**
   *  Looks up the string to obtain scoped keys, if any, matching it; then sets up watches
   *  for each matching task or setting key. If no keys match, still calls the value listener
   *  one time with an error.
   *  @param name       Name to look up keys for and then watch those keys.
   *  @param listener  A function that is called when the setting value changes.
   *  @param ex        The execution context on which to call the listener.
   *
   * @note To preserve ordering of notifications, use the same single-threaded ExecutionContext
   *       for all listeners on the same SbtClient.
   */
  def rawWatch(name: String)(l: RawValueListener)(implicit ex: ExecutionContext): Subscription
  /**
   * Like rawWatch(String) except that it calls rawLazyWatch() for each discovered key instead of rawWatch().
   *
   *  @param name       Name to look up keys for and then watch those keys.
   *  @param listener  A function that is called when the setting value changes.
   *  @param ex        The execution context on which to call the listener.
   *
   *  @note To preserve ordering of notifications, use the same single-threaded ExecutionContext
   *        for all listeners on the same SbtClient.
   */
  def rawLazyWatch(name: String)(l: RawValueListener)(implicit ex: ExecutionContext): Subscription

  /**
   * Kills the running instance of the sbt server (by attempting to issue a kill message).
   * This does not alter whether or not the SbtConnector tries to reconnect.
   */
  def requestSelfDestruct(): Unit

  /** Returns true if the client is closed. */
  def isClosed: Boolean
}

object SbtClient {
  /** Create an SbtClient attached to the provided channel. This can only be done once per channel. */
  def apply(channel: SbtChannel): SbtClient =
    new impl.SimpleSbtClient(channel)
}
