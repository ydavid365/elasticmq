package org.elasticmq.rest.sqs

import xml._
import java.security.MessageDigest
import com.typesafe.scalalogging.slf4j.Logging
import collection.mutable.ArrayBuffer
import spray.routing.SimpleRoutingApp
import akka.actor.{Props, ActorRef, ActorSystem}
import spray.can.server.ServerSettings
import akka.util.Timeout
import scala.concurrent.{Await, Future}
import org.elasticmq.rest.sqs.directives.ElasticMQDirectives
import spray.can.Http
import akka.io.IO
import org.elasticmq.rest.sqs.Constants._
import scala.xml.EntityRef
import org.elasticmq.QueueData
import org.elasticmq.NodeAddress
import com.typesafe.config.ConfigFactory
import org.elasticmq.actor.QueueManagerActor
import org.elasticmq.util.NowProvider
import scala.concurrent.duration._

/**
 * By default:
 * <li>
 *  <ul>for `socketAddress`: when started, the server will bind to `localhost:9324`</ul>
 *  <ul>for `serverAddress`: returned queue addresses will use `http://localhost:9324` as the base address.</ul>
 *  <ul>for `sqsLimits`: relaxed
 * </li>
 */
object SQSRestServerBuilder extends TheSQSRestServerBuilder(None, None, "", 9324, NodeAddress(), SQSLimits.Strict)

case class TheSQSRestServerBuilder(providedActorSystem: Option[ActorSystem],
                                   providedQueueManagerActor: Option[ActorRef],
                                   interface: String,
                                   port: Int,
                                   serverAddress: NodeAddress,
                                   sqsLimits: SQSLimits.Value) extends Logging {

  /**
   * @param _actorSystem Optional actor system. If one is provided, it will be used to create ElasticMQ and Spray
   *                     actors, but its lifecycle (shutdown) will be not managed by the server. If one is not
   *                     provided, an actor system will be created, and its lifecycle will be bound to the server's
   *                     lifecycle.
   */
  def withActorSystem(_actorSystem: ActorSystem) = this.copy(providedActorSystem = Some(_actorSystem))

  /**
   * @param _queueManagerActor Optional "main" ElasticMQ actor.
   */
  def withQueueManagerActor(_queueManagerActor: ActorRef) = this.copy(providedQueueManagerActor = Some(_queueManagerActor))

  /**
   * @param _interface Hostname to which the server will bind.
   */
  def withInterface(_interface: String) = this.copy(interface = _interface)

  /**
   * @param _port Port to which the server will bind.
   */
  def withPort(_port: Int) = this.copy(port = _port)

  /**
   * @param _serverAddress Address which will be returned as the queue address. Requests to this address
   *                       should be routed to this server.
   */
  def withServerAddress(_serverAddress: NodeAddress) = this.copy(serverAddress = _serverAddress)

  /**
   * @param _sqsLimits Should "real" SQS limits be used (strict), or should they be relaxed where possible (regarding
   *                   e.g. message size).
   */
  def withSQSLimits(_sqsLimits: SQSLimits.Value) = this.copy(sqsLimits = _sqsLimits)

  def start(): SQSRestServer = {
    val (theActorSystem, stopActorSystem) = getOrCreateActorSystem()
    val theQueueManagerActor = getOrCreateQueueManagerActor(theActorSystem)
    val theServerAddress = serverAddress
    val theLimits = sqsLimits

    implicit val implictActorSystem = theActorSystem

    val env = new QueueManagerActorModule
      with QueueURLModule
      with SQSLimitsModule
      with BatchRequestsModule
      with ElasticMQDirectives
      with CreateQueueDirectives
      with DeleteQueueDirectives
      with QueueAttributesDirectives
      with ListQueuesDirectives
      with SendMessageDirectives
      with SendMessageBatchDirectives
      with ReceiveMessageDirectives
      with DeleteMessageDirectives
      with DeleteMessageBatchDirectives
      with ChangeMessageVisibilityDirectives
      with ChangeMessageVisibilityBatchDirectives
      with GetQueueUrlDirectives
      with AttributesModule {

      lazy val actorSystem = theActorSystem
      lazy val queueManagerActor = theQueueManagerActor
      lazy val serverAddress = theServerAddress
      lazy val sqsLimits = theLimits
      lazy val timeout = Timeout(ServerSettings(actorSystem).requestTimeout.toMillis)
    }

    import env._
    val rawRoutes =
        // 1. Sending, receiving, deleting messages
        sendMessage ~
        sendMessageBatch ~
        receiveMessage ~
        deleteMessage ~
        deleteMessageBatch ~
        // 2. Getting, creating queues
        getQueueUrl ~
        createQueue ~
        listQueues ~
        // 3. Other
        changeMessageVisibility ~
        changeMessageVisibilityBatch ~
        deleteQueue ~
        getQueueAttributes ~
        setQueueAttributes

    val config = new ElasticMQConfig

    val routes = if (config.debug) {
      logRequestResponse("") {
        rawRoutes
      }
    } else rawRoutes

    val serviceActorName = s"elasticmq-rest-sqs-$port"

    val app = new SimpleRoutingApp {}
    val appStartFuture = app.startServer(interface, port, serviceActorName) {
      handleServerExceptions {
        routes
      }
    }

    TheSQSRestServerBuilder.this.logger.info("Started SQS rest server, bind address %s:%d, visible server address %s"
      .format(interface, port, theServerAddress.fullAddress))

    SQSRestServer(appStartFuture, () => {
      import akka.pattern.ask
      val future = IO(Http).ask(Http.CloseAll)(Timeout(10000L))
      future.map(v => { stopActorSystem(); v })
      future
    })
  }

  private def getOrCreateActorSystem() = {
    providedActorSystem
      .map((_, () => ()))
      .getOrElse {
      val actorSystem = ActorSystem("elasticmq")
      (actorSystem, () => {
        actorSystem.shutdown()
        actorSystem.awaitTermination()
      })
    }
  }

  private def getOrCreateQueueManagerActor(actorSystem: ActorSystem) = {
    providedQueueManagerActor.getOrElse(actorSystem.actorOf(Props(new QueueManagerActor(new NowProvider()))))
  }
}

case class SQSRestServer(startFuture: Future[Any], stopAndGetFuture: () => Future[Any]) {
  def waitUntilStarted() = {
    Await.result(startFuture, 1.minute)
  }

  def stopAndWait() = {
    val stopFuture = stopAndGetFuture()
    Await.result(stopFuture, 1.minute)
  }
}

object Constants {
  val EmptyRequestId = "00000000-0000-0000-0000-000000000000"
  val SqsDefaultVersion = "2012-11-05"
  val ReceiptHandleParameter = "ReceiptHandle"
  val VisibilityTimeoutParameter = "VisibilityTimeout"
  val DelaySecondsAttribute = "DelaySeconds"
  val ReceiveMessageWaitTimeSecondsAttribute = "ReceiveMessageWaitTimeSeconds"
  val IdSubParameter = "Id"
  val InvalidParameterValueErrorName = "InvalidParameterValue"
}

object ParametersUtil {
  implicit class ParametersParser(parameters: Map[String, String]) {
    def parseOptionalLong(name: String) = {
      val param = parameters.get(name)
      try {
        param.map(_.toLong)
      } catch {
        case e: NumberFormatException => throw SQSException.invalidParameterValue
      }
    }
  }
}

object MD5Util {
  def md5Digest(s: String) = {
    val md5 = MessageDigest.getInstance("MD5")
    md5.reset()
    md5.update(s.getBytes)
    md5.digest().map(0xFF & _).map { "%02x".format(_) }.foldLeft(""){_ + _}
  }
}

object XmlUtil {
  private val CR = EntityRef("#13")

  def convertTexWithCRToNodeSeq(text: String): NodeSeq = {
    val parts = text.split("\r")
    val partsCount = parts.length
    if (partsCount == 1) {
      Text(text)
    } else {
      val combined = new ArrayBuffer[scala.xml.Node]()
      for (i <- 0 until partsCount) {
        combined += Text(parts(i))
        if (i != partsCount - 1) {
          combined += CR
        }
      }

      combined
    }
  }
}

trait QueueManagerActorModule {
  def queueManagerActor: ActorRef
}

trait QueueURLModule {
  def serverAddress: NodeAddress

  def queueURL(queueData: QueueData) = serverAddress.fullAddress + "/queue/" + queueData.name
  def queueURL(queueName: String) = serverAddress.fullAddress + "/queue/" + queueName
}

object SQSLimits extends Enumeration {
  val Strict = Value
  val Relaxed = Value
}

trait SQSLimitsModule {
  def sqsLimits: SQSLimits.Value
  def ifStrictLimits(condition: => Boolean)(exception: String) {
    if (sqsLimits == SQSLimits.Strict && condition) {
      throw new SQSException(exception)
    }
  }

  def verifyMessageWaitTime(messageWaitTimeOpt: Option[Long]) {
    messageWaitTimeOpt.foreach { messageWaitTime =>
      if (messageWaitTime < 0) {
        throw SQSException.invalidParameterValue
      }

      ifStrictLimits(messageWaitTime > 20 || messageWaitTime < 1) {
        InvalidParameterValueErrorName
      }
    }
  }
}

class ElasticMQConfig {
  private lazy val rootConfig = ConfigFactory.load()
  private lazy val elasticMQConfig = rootConfig.getConfig("elasticmq")

  lazy val debug = elasticMQConfig.getBoolean("debug")
}
