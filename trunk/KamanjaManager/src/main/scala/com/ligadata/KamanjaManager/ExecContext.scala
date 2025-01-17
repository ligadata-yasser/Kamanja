
/*
 * Copyright 2015 ligaDATA
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.ligadata.KamanjaManager

import com.ligadata.KamanjaBase.{ EnvContext, DataDelimiters, TransactionContext, InputData }
import com.ligadata.InputOutputAdapterInfo.{ ExecContext, InputAdapter, OutputAdapter, ExecContextObj, PartitionUniqueRecordKey, PartitionUniqueRecordValue, InputAdapterCallerContext }
import com.ligadata.KvBase.{ Key }

import org.apache.logging.log4j.{ Logger, LogManager }
import org.json4s._
import org.json4s.JsonDSL._
import org.json4s.jackson.JsonMethods._
import scala.collection.mutable.ArrayBuffer
import com.ligadata.Exceptions.{ FatalAdapterException, MessagePopulationException, StackTrace }

import com.ligadata.transactions._

import com.ligadata.transactions._

// There are no locks at this moment. Make sure we don't call this with multiple threads for same object
class ExecContextImpl(val input: InputAdapter, val curPartitionKey: PartitionUniqueRecordKey, val callerCtxt: InputAdapterCallerContext) extends ExecContext {
  private val LOG = LogManager.getLogger(getClass);
  if (callerCtxt.isInstanceOf[KamanjaInputAdapterCallerContext] == false) {
    throw new Exception("Handling only KamanjaInputAdapterCallerContext in ValidateExecCtxtImpl")
  }

  NodeLevelTransService.init(KamanjaConfiguration.zkConnectString, KamanjaConfiguration.zkSessionTimeoutMs, KamanjaConfiguration.zkConnectionTimeoutMs, KamanjaConfiguration.zkNodeBasePath, KamanjaConfiguration.txnIdsRangeForNode, KamanjaConfiguration.dataDataStoreInfo, KamanjaConfiguration.jarPaths)

  private val kamanjaCallerCtxt = callerCtxt.asInstanceOf[KamanjaInputAdapterCallerContext]
  private val transService = new SimpleTransService
  transService.init(KamanjaConfiguration.txnIdsRangeForPartition)

  private val xform = new TransformMessageData
  private val engine = new LearningEngine(input, curPartitionKey)
  private var previousLoader: com.ligadata.Utils.KamanjaClassLoader = null
  private val allOutputAdaptersNames = if (kamanjaCallerCtxt.outputAdapters != null) kamanjaCallerCtxt.outputAdapters.map(o => o.inputConfig.Name.toLowerCase) else Array[String]()
  private val allOuAdapters = if (kamanjaCallerCtxt.outputAdapters != null) kamanjaCallerCtxt.outputAdapters.map(o => (o.inputConfig.Name.toLowerCase, o)).toMap else Map[String, OutputAdapter]()
  private val failedEventsAdapter =
    if (input != null && input.inputConfig != null && input.inputConfig.failedEventsAdapterName != null && kamanjaCallerCtxt.failedEventsAdapters != null && kamanjaCallerCtxt.failedEventsAdapters.size > 0) {
      val failedEventsAdapterName = input.inputConfig.failedEventsAdapterName.trim()
      var failedAdap: OutputAdapter = null
      if (failedEventsAdapterName.size > 0) {
        val filtred = kamanjaCallerCtxt.failedEventsAdapters.filter(a => (a.inputConfig.Name.trim().compareToIgnoreCase(failedEventsAdapterName) == 0))
        if (filtred.size > 0) {
          LOG.debug("Found FailedEventsAdapter %s for InputAdapter %s".format(input.inputConfig.failedEventsAdapterName, input.inputConfig.Name))
          failedAdap = filtred(0)
        } else {
          LOG.warn("Not found FailedEventsAdapter %s for InputAdapter %s. Ignoring message failed in InputAdapter %s".format(input.inputConfig.failedEventsAdapterName, input.inputConfig.Name, input.inputConfig.Name))
        }
      }
      failedAdap
    } else {
      null
    }

  private val failedEventDtFormat = new java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSZ")

  private val adapterInfoMap = ProcessedAdaptersInfo.getOneInstance(this.hashCode(), true)

  private def SendFailedEvent(data: Array[Byte], format: String, associatedMsg: String, uk: String, uv: String, e: Throwable): Unit = {
    if (failedEventsAdapter == null)
      return

    val failedTm = failedEventDtFormat.format(new java.util.Date(System.currentTimeMillis))

    val evntData = new String(data)

    val failMsg = if (e != null) e.getMessage else ""
    val stackTrace = if (e != null) StackTrace.ThrowableTraceString(e) else ""

    val out = ("MessageType" -> associatedMsg) ~
      ("Deserializer" -> format) ~
      ("SourceAdapter" -> input.inputConfig.Name) ~
      ("FailedAt" -> failedTm) ~
      ("EventData" -> evntData) ~
      ("Partition" -> ("Key" -> uk) ~ ("Value" -> uv)) ~
      ("Failure" -> ("Message" -> failMsg) ~ ("StackTrace" -> stackTrace))
    val json = compact(render(out))

    try {
      failedEventsAdapter.send(json, "")
    } catch {
      case fae: FatalAdapterException => {
        LOG.error("Failed to send data to failedevent adapter:" + failedEventsAdapter.inputConfig.Name, fae)
      }
      case e: Exception => {
        LOG.error("Failed to send data to failedevent adapter:" + failedEventsAdapter.inputConfig.Name, e)
      }
      case t: Throwable => {
        LOG.error("Failed to send data to failedevent adapter:" + failedEventsAdapter.inputConfig.Name, t)
      }
    }
  }

  def execute(data: Array[Byte], format: String, uniqueKey: PartitionUniqueRecordKey, uniqueVal: PartitionUniqueRecordValue, readTmNanoSecs: Long, readTmMilliSecs: Long, ignoreOutput: Boolean, associatedMsg: String, delimiters: DataDelimiters): Unit = {
    try {
      val curLoader = KamanjaConfiguration.metadataLoader.loader // Protecting from changing it between below statements
      if (curLoader != null && previousLoader != curLoader) { // Checking for every messages and setting when changed. We can set for every message, but the problem is it tries to do SecurityManager check every time.
        Thread.currentThread().setContextClassLoader(curLoader);
        previousLoader = curLoader
      }
    } catch {
      case e: Exception => {
        LOG.error("Failed to setContextClassLoader. Reason:%s Message:%s".format(e.getCause, e.getMessage))
      }
    }

    try {
      val uk = uniqueKey.Serialize
      val uv = uniqueVal.Serialize
      val transId = transService.getNextTransId
      val txnCtxt = new TransactionContext(transId, kamanjaCallerCtxt.gNodeContext, data, uk)
      LOG.debug("Processing uniqueKey:%s, uniqueVal:%s, Datasize:%d".format(uk, uv, data.size))

      var outputResults = ArrayBuffer[(String, String, String)]() // Adapter/Queue name, Partition Key & output message 

      try {
        val transformStartTime = System.nanoTime
        var xformedmsgs = Array[(String, MsgContainerObjAndTransformInfo, InputData)]()
        try {
          xformedmsgs = xform.execute(data, format, associatedMsg, delimiters, uk, uv)
        } catch {
          case e: Exception => {
            SendFailedEvent(data, format, associatedMsg, uk, uv, e)
          }
          case e: Throwable => {
            SendFailedEvent(data, format, associatedMsg, uk, uv, e)
          }
        }
        LOG.info(ManagerUtils.getComponentElapsedTimeStr("Transform", uv, readTmNanoSecs, transformStartTime))
        if (xformedmsgs != null) {
          var xformedMsgCntr = 0
          val totalXformedMsgs = xformedmsgs.size
          xformedmsgs.foreach(xformed => {
            xformedMsgCntr += 1
            try {
              var output = engine.execute(transId, data, xformed._1, xformed._2, xformed._3, txnCtxt, readTmNanoSecs, readTmMilliSecs, uk, uv, xformedMsgCntr, totalXformedMsgs, ignoreOutput, allOutputAdaptersNames)
              if (output != null) {
                outputResults ++= output
              }
            } catch {
              case e: MessagePopulationException => {
                SendFailedEvent(data, format, xformed._1, uk, uv, e)
              }
              case e: Exception => {
                LOG.error("Failed to execute models after creating message", e)
              }
              case e: Throwable => {
                LOG.error("Failed to execute models after creating message", e)
              }
            }
          })
        }
      } catch {
        case e: Exception => {
          LOG.error("Failed to execute message. Reason:%s Message:%s".format(e.getCause, e.getMessage))
        }
      } finally {
        // LOG.debug("UniqueKeyValue:%s => %s".format(uk, uv))
        val dispMsg = if (KamanjaConfiguration.waitProcessingTime > 0) new String(data) else ""
        if (KamanjaConfiguration.waitProcessingTime > 0 && KamanjaConfiguration.waitProcessingSteps(1)) {
          try {
            LOG.debug("Started Waiting in Step 1 (before committing data) for Message:" + dispMsg)
            Thread.sleep(KamanjaConfiguration.waitProcessingTime)
            LOG.debug("Done Waiting in Step 1 (before committing data) for Message:" + dispMsg)
          } catch {
            case e: Exception => {
              LOG.debug("Failed to wait", e)
            }
          }
        }

        val commitStartTime = System.nanoTime
        // 
        // kamanjaCallerCtxt.envCtxt.setAdapterUniqueKeyValue(transId, uk, uv, outputResults.toList)
        val forceCommitVal = txnCtxt.getValue("forcecommit")
        val forceCommitFalg = forceCommitVal != null
        val containerData = if (forceCommitFalg || kamanjaCallerCtxt.gNodeContext.getEnvCtxt.EnableEachTransactionCommit) kamanjaCallerCtxt.gNodeContext.getEnvCtxt.getChangedData(transId, false, true) else scala.collection.immutable.Map[String, List[Key]]() // scala.collection.immutable.Map[String, List[List[String]]]
        kamanjaCallerCtxt.gNodeContext.getEnvCtxt.commitData(transId, uk, uv, outputResults.toList, forceCommitFalg)

        // Set the uk & uv
        if (adapterInfoMap != null)
          adapterInfoMap(uk) = uv
        LOG.info(ManagerUtils.getComponentElapsedTimeStr("Commit", uv, readTmNanoSecs, commitStartTime))

        if (KamanjaConfiguration.waitProcessingTime > 0 && KamanjaConfiguration.waitProcessingSteps(2)) {
          try {
            LOG.debug("Started Waiting in Step 2 (before writing to output adapter) for Message:" + dispMsg)
            Thread.sleep(KamanjaConfiguration.waitProcessingTime)
            LOG.debug("Done Waiting in Step 2 (before writing to output adapter) for Message:" + dispMsg)
          } catch {
            case e: Exception => {
              LOG.debug("Failed to wait", e)
            }
          }
        }

        val sendOutStartTime = System.nanoTime
        val outputs = outputResults.groupBy(_._1)

        var remOutputs = outputs.toArray
        var failedWaitTime = 15000 // Wait time starts at 15 secs
        val maxFailedWaitTime = 60000 // Max Wait time 60 secs

        while (remOutputs.size > 0) {
          var failedOutputs = ArrayBuffer[(String, ArrayBuffer[(String, String, String)])]()
          remOutputs.foreach(output => {
            val oadap = allOuAdapters.getOrElse(output._1, null)
            LOG.debug("Sending data => " + output._2.map(o => o._1 + "~~~" + o._2 + "~~~" + o._3).mkString("###"))
            if (oadap != null) {
              try {
                oadap.send(output._2.map(out => out._3.getBytes("UTF8")).toArray, output._2.map(out => out._2.getBytes("UTF8")).toArray)
              } catch {
                case fae: FatalAdapterException => {
                  LOG.error("Failed to send data to output adapter:" + oadap.inputConfig.Name, fae)
                  failedOutputs += output
                }
                case e: Exception => {
                  LOG.error("Failed to send data to output adapter:" + oadap.inputConfig.Name, e)
                  failedOutputs += output
                }
                case t: Throwable => {
                  LOG.error("Failed to send data to output adapter:" + oadap.inputConfig.Name, t)
                  failedOutputs += output
                }
              }
            }
          })

          remOutputs = failedOutputs.toArray

          if (remOutputs.size > 0) {
            try {
              LOG.error("Failed to send %d outputs. Waiting for another %d milli seconds and going to start them again.".format(remOutputs.size, failedWaitTime))
              Thread.sleep(failedWaitTime)
            } catch {
              case e: Exception => {

              }
            }
            // Adjust time for next time
            if (failedWaitTime < maxFailedWaitTime) {
              failedWaitTime = failedWaitTime * 2
              if (failedWaitTime > maxFailedWaitTime)
                failedWaitTime = maxFailedWaitTime
            }
          }
        }

        if (KamanjaConfiguration.waitProcessingTime > 0 && KamanjaConfiguration.waitProcessingSteps(3)) {
          try {
            LOG.debug("Started Waiting in Step 3 (before removing sent data) for Message:" + dispMsg)
            Thread.sleep(KamanjaConfiguration.waitProcessingTime)
            LOG.debug("Done Waiting in Step 3 (before removing sent data) for Message:" + dispMsg)
          } catch {
            case e: Exception => {
              LOG.debug("Failed to Wait.", e)
            }
          }
        }

        // kamanjaCallerCtxt.envCtxt.removeCommittedKey(transId, uk)
        LOG.info(ManagerUtils.getComponentElapsedTimeStr("SendResults", uv, readTmNanoSecs, sendOutStartTime))

        if (containerData != null && containerData.size > 0) {
          val datachangedata = ("txnid" -> transId.toString) ~
            ("changeddatakeys" -> containerData.map(kv =>
              ("C" -> kv._1) ~
                ("K" -> kv._2.map(k =>
                  ("tm" -> k.timePartition) ~
                    ("bk" -> k.bucketKey.toList) ~
                    ("tx" -> k.transactionId) ~
                    ("rid" -> k.rowId)))))
          val sendJson = compact(render(datachangedata))
          // Do we need to log this?
          KamanjaLeader.SetNewDataToZkc(KamanjaConfiguration.zkNodeBasePath + "/datachange", sendJson.getBytes("UTF8"))
        }
      }
    } catch {
      case e: Exception => {
        LOG.error("Failed to serialize uniqueKey/uniqueVal.", e)
      }
    }
  }
}

object ExecContextObjImpl extends ExecContextObj {
  def CreateExecContext(input: InputAdapter, curPartitionKey: PartitionUniqueRecordKey, callerCtxt: InputAdapterCallerContext): ExecContext = {
    new ExecContextImpl(input, curPartitionKey, callerCtxt)
  }
}

//--------------------------------

object CollectKeyValsFromValidation {
  // Key to (Value, xCntr, xTotl & TxnId)
  private[this] val keyVals = scala.collection.mutable.Map[String, (String, Int, Int, Long)]()
  private[this] val lock = new Object()
  private[this] var lastUpdateTime = System.nanoTime

  def addKeyVals(uKStr: String, uVStr: String, xCntr: Int, xTotl: Int, txnId: Long): Unit = lock.synchronized {
    val klc = uKStr.toLowerCase
    val existVal = keyVals.getOrElse(klc, null)
    if (existVal == null || txnId > existVal._4) {
      keyVals(klc) = (uVStr, xCntr, xTotl, txnId)
    }
    lastUpdateTime = System.nanoTime
  }

  def get: scala.collection.immutable.Map[String, (String, Int, Int, Long)] = lock.synchronized {
    keyVals.toMap
  }

  def clear: Unit = lock.synchronized {
    keyVals.clear
    lastUpdateTime = System.nanoTime
  }

  def getLastUpdateTime: Long = lock.synchronized {
    lastUpdateTime
  }
}

// There are no locks at this moment. Make sure we don't call this with multiple threads for same object
class ValidateExecCtxtImpl(val input: InputAdapter, val curPartitionKey: PartitionUniqueRecordKey, val callerCtxt: InputAdapterCallerContext) extends ExecContext {
  val LOG = LogManager.getLogger(getClass);

  if (callerCtxt.isInstanceOf[KamanjaInputAdapterCallerContext] == false) {
    throw new Exception("Handling only KamanjaInputAdapterCallerContext in ValidateExecCtxtImpl")
  }

  val kamanjaCallerCtxt = callerCtxt.asInstanceOf[KamanjaInputAdapterCallerContext]

  NodeLevelTransService.init(KamanjaConfiguration.zkConnectString, KamanjaConfiguration.zkSessionTimeoutMs, KamanjaConfiguration.zkConnectionTimeoutMs, KamanjaConfiguration.zkNodeBasePath, KamanjaConfiguration.txnIdsRangeForNode, KamanjaConfiguration.dataDataStoreInfo, KamanjaConfiguration.jarPaths)

  val xform = new TransformMessageData
  val engine = new LearningEngine(input, curPartitionKey)
  val transService = new SimpleTransService

  transService.init(KamanjaConfiguration.txnIdsRangeForPartition)

  private def getAllModelResults(data: Any): Array[Map[String, Any]] = {
    val results = new ArrayBuffer[Map[String, Any]]()
    try {
      data match {
        case m: Map[_, _] => {
          try {
            results += m.asInstanceOf[Map[String, Any]]
          } catch {
            case e: Exception => {
              LOG.error("Failed reason %s, message %s".format(e.getCause, e.getMessage))
            }
          }
        }
        case l: List[Any] => {
          try {
            val data = l.asInstanceOf[List[Any]]
            data.foreach(d => {
              results ++= getAllModelResults(d)
            })
          } catch {
            case e: Exception => {
              LOG.error("Failed reason %s, message %s".format(e.getCause, e.getMessage))
            }
          }
        }
      }
    } catch {
      case e: Exception => {
        LOG.error("Failed to collect model results. Reason %s, message %s".format(e.getCause, e.getMessage))
      }
    }

    results.toArray
  }

  def execute(data: Array[Byte], format: String, uniqueKey: PartitionUniqueRecordKey, uniqueVal: PartitionUniqueRecordValue, readTmNanoSecs: Long, readTmMilliSecs: Long, ignoreOutput: Boolean, associatedMsg: String, delimiters: DataDelimiters): Unit = {
    try {
      val transId = transService.getNextTransId
      try {
        val inputData = new String(data)
        val json = parse(inputData)
        if (json == null || json.values == null) {
          LOG.error("Invalid JSON data : " + inputData)
          return
        }
        val parsed_json = json.values.asInstanceOf[Map[String, Any]]
        if (parsed_json.size != 1) {
          LOG.error("Expecting only one ModelsResult in JSON data : " + inputData)
          return
        }
        val mdlRes = parsed_json.head._1
        if (mdlRes == null || mdlRes.compareTo("ModelsResult") != 0) {
          LOG.error("Expecting only ModelsResult as key in JSON data : " + inputData)
          return
        }

        val results = getAllModelResults(parsed_json.head._2)

        results.foreach(allVals => {
          val uK = allVals.getOrElse("uniqKey", null)
          val uV = allVals.getOrElse("uniqVal", null)

          if (uK == null || uV == null) {
            LOG.error("Not found uniqKey & uniqVal in ModelsResult. JSON string : " + inputData)
            return
          }

          val uKStr = uK.toString
          val uVStr = uV.toString
          val xCntr = allVals.getOrElse("xformCntr", "1").toString.toInt
          val xTotl = allVals.getOrElse("xformTotl", "1").toString.toInt
          val txnId = allVals.getOrElse("TxnId", "0").toString.toLong
          val existVal = CollectKeyValsFromValidation.addKeyVals(uKStr, uVStr, xCntr, xTotl, txnId)
        })
      } catch {
        case e: Exception => {
          LOG.error("Failed to execute message. Reason:%s Message:%s".format(e.getCause, e.getMessage))
        }
      } finally {
        // LOG.debug("UniqueKeyValue:%s => %s".format(uk, uv))
        kamanjaCallerCtxt.gNodeContext.getEnvCtxt.commitData(transId, null, null, null, false)
      }
    } catch {
      case e: Exception => {
        LOG.error("Failed to serialize uniqueKey/uniqueVal", e)
      }
    }
  }
}

object ValidateExecContextObjImpl extends ExecContextObj {
  def CreateExecContext(input: InputAdapter, curPartitionKey: PartitionUniqueRecordKey, callerCtxt: InputAdapterCallerContext): ExecContext = {
    new ValidateExecCtxtImpl(input, curPartitionKey, callerCtxt)
  }
}

