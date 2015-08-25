package com.ligadata.metadataapiservice

import akka.actor.{Actor, ActorRef}
import akka.event.Logging
import akka.io.IO
import com.ligadata.kamanja.metadata._
import spray.routing.RequestContext
import spray.httpx.SprayJsonSupport
import spray.client.pipelining._

import scala.util.{ Success, Failure }

import com.ligadata.MetadataAPI._

object GetHeartbeatService {
  case class Process(nodeIds:String)
}

/**
 * @author danielkozin
 */
class GetHeartbeatService(requestContext: RequestContext, userid:Option[String], password:Option[String], cert:Option[String]) extends Actor  {
  import GetHeartbeatService._ 
  import system.dispatcher
  
  implicit val system = context.system
  val log = Logging(system, getClass)
  val APIName = "GetHeartbeatService"
  
  def receive = {
    case Process(nodeId) =>
      process(nodeId)
      context.stop(self)
  }
  
  def process(nodeIds:String): Unit = {
    // NodeIds is a JSON array of nodeIds.
    if (nodeIds == null || (nodeIds != null && nodeIds.length == 0))
      requestContext.complete(new ApiResult(ErrorCodeConstants.Failure, APIName, null, "Invalid BODY in a POST request.  Expecting either an array of nodeIds or an empty array for all").toString)  
  
    if (!MetadataAPIImpl.checkAuth(userid,password,cert, MetadataAPIImpl.getPrivilegeName("get","heartbeat"))) {
      requestContext.complete(new ApiResult(ErrorCodeConstants.Failure, APIName, null, "Error:Checking Heartbeat is not allowed for this user").toString )
    } else {
      val apiResult = MetadataAPIImpl.getHealthCheck(nodeIds)  
      requestContext.complete(apiResult)      
    }
  }  
  
}