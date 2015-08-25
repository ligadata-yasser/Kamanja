
package com.ligadata.KamanjaManager

import org.apache.log4j.Logger
import com.ligadata.kamanja.metadata._
import com.ligadata.kamanja.metadata.MdMgr._
import com.ligadata.KamanjaBase.{ EnvContext }
import com.ligadata.InputOutputAdapterInfo.{ ExecContext, InputAdapter, OutputAdapter, ExecContextObj, PartitionUniqueRecordKey, PartitionUniqueRecordValue, InputAdapterCallerContext }
import com.ligadata.Utils.{ Utils, KamanjaClassLoader, KamanjaLoaderInfo }
import scala.collection.mutable.ArrayBuffer
import com.ligadata.Serialize.{ JDataStore, JZKInfo, JEnvCtxtJsonStr }
import com.ligadata.InputOutputAdapterInfo.{ ExecContext, InputAdapter, InputAdapterObj, OutputAdapter, OutputAdapterObj, ExecContextObj, PartitionUniqueRecordKey, PartitionUniqueRecordValue, AdapterConfiguration }

import org.json4s._
import org.json4s.JsonDSL._
import org.json4s.native.JsonMethods._
import java.io.{ File }
import com.ligadata.Exceptions.StackTrace

class KamanjaInputAdapterCallerContext extends InputAdapterCallerContext {
  var outputAdapters: Array[OutputAdapter] = _
  var envCtxt: EnvContext = _
}

// This is shared by multiple threads to read (because we are not locking). We create this only once at this moment while starting the manager
object KamanjaMdCfg {
  private[this] val LOG = Logger.getLogger(getClass);
  private[this] val mdMgr = GetMdMgr

  def InitConfigInfo: Boolean = {
    val nd = mdMgr.Nodes.getOrElse(KamanjaConfiguration.nodeId.toString, null)
    if (nd == null) {
      LOG.error("Node %d not found in metadata".format(KamanjaConfiguration.nodeId))
      return false
    }

    KamanjaConfiguration.clusterId = nd.ClusterId

    val cluster = mdMgr.ClusterCfgs.getOrElse(nd.ClusterId, null)
    if (cluster == null) {
      LOG.error("Cluster not found for Node %d  & ClusterId : %s".format(KamanjaConfiguration.nodeId, nd.ClusterId))
      return false
    }

    val dataStore = cluster.cfgMap.getOrElse("DataStore", null)
    if (dataStore == null) {
      LOG.error("DataStore not found for Node %d  & ClusterId : %s".format(KamanjaConfiguration.nodeId, nd.ClusterId))
      return false
    }

    val statusInfo = cluster.cfgMap.getOrElse("StatusInfo", null)
    if (statusInfo == null) {
      LOG.error("StatusInfo not found for Node %d  & ClusterId : %s".format(KamanjaConfiguration.nodeId, nd.ClusterId))
      return false
    }

    val zooKeeperInfo = cluster.cfgMap.getOrElse("ZooKeeperInfo", null)
    if (zooKeeperInfo == null) {
      LOG.error("ZooKeeperInfo not found for Node %d  & ClusterId : %s".format(KamanjaConfiguration.nodeId, nd.ClusterId))
      return false
    }

    KamanjaConfiguration.jarPaths = if (nd.JarPaths == null) Set[String]() else nd.JarPaths.map(str => str.replace("\"", "").trim).filter(str => str.size > 0).toSet
    if (KamanjaConfiguration.jarPaths.size == 0) {
      LOG.error("Not found valid JarPaths.")
      return false
    }

    KamanjaConfiguration.nodePort = nd.NodePort
    if (KamanjaConfiguration.nodePort <= 0) {
      LOG.error("Not found valid nodePort. It should be greater than 0")
      return false
    }

    KamanjaConfiguration.dataDataStoreInfo = dataStore
    KamanjaConfiguration.statusDataStoreInfo = statusInfo

    implicit val jsonFormats: Formats = DefaultFormats
    val zKInfo = parse(zooKeeperInfo).extract[JZKInfo]

    /*
    // DataStore & StatusInfo & ZooKeeperInfo
    val dataStoreInfo = parse(dataStore).extract[JDataStore]
    val statusStoreInfo = parse(statusInfo).extract[JDataStore]
    KamanjaConfiguration.dataStoreType = dataStoreInfo.StoreType.replace("\"", "").trim
    if (KamanjaConfiguration.dataStoreType.size == 0) {
      LOG.error("Not found valid DataStoreType.")
      return false
    }

    KamanjaConfiguration.dataSchemaName = dataStoreInfo.SchemaName.replace("\"", "").trim
    if (KamanjaConfiguration.dataSchemaName.size == 0) {
      LOG.error("Not found valid DataSchemaName.")
      return false
    }

    KamanjaConfiguration.dataLocation = dataStoreInfo.Location.replace("\"", "").trim
    if (KamanjaConfiguration.dataLocation.size == 0) {
      LOG.error("Not found valid DataLocation.")
      return false
    }

    KamanjaConfiguration.adapterSpecificConfig = if (dataStoreInfo.AdapterSpecificConfig == None || dataStoreInfo.AdapterSpecificConfig == null) "" else dataStoreInfo.AdapterSpecificConfig.get.trim

    KamanjaConfiguration.statusInfoStoreType = statusStoreInfo.StoreType.replace("\"", "").trim
    if (KamanjaConfiguration.statusInfoStoreType.size == 0) {
      LOG.error("Not found valid Status Information StoreType.")
      return false
    }

    KamanjaConfiguration.statusInfoSchemaName = statusStoreInfo.SchemaName.replace("\"", "").trim
    if (KamanjaConfiguration.statusInfoSchemaName.size == 0) {
      LOG.error("Not found valid Status Information SchemaName.")
      return false
    }

    KamanjaConfiguration.statusInfoLocation = statusStoreInfo.Location.replace("\"", "").trim
    if (KamanjaConfiguration.statusInfoLocation.size == 0) {
      LOG.error("Not found valid Status Information Location.")
      return false
    }

    KamanjaConfiguration.statusInfoAdapterSpecificConfig = if (statusStoreInfo.AdapterSpecificConfig == None || statusStoreInfo.AdapterSpecificConfig == null) "" else statusStoreInfo.AdapterSpecificConfig.get.trim
*/

    KamanjaConfiguration.zkConnectString = zKInfo.ZooKeeperConnectString.replace("\"", "").trim
    KamanjaConfiguration.zkNodeBasePath = zKInfo.ZooKeeperNodeBasePath.replace("\"", "").trim
    KamanjaConfiguration.zkSessionTimeoutMs = if (zKInfo.ZooKeeperSessionTimeoutMs == None || zKInfo.ZooKeeperSessionTimeoutMs == null) 0 else zKInfo.ZooKeeperSessionTimeoutMs.get.toString.toInt
    KamanjaConfiguration.zkConnectionTimeoutMs = if (zKInfo.ZooKeeperConnectionTimeoutMs == None || zKInfo.ZooKeeperConnectionTimeoutMs == null) 0 else zKInfo.ZooKeeperConnectionTimeoutMs.get.toString.toInt

    // Taking minimum values in case if needed
    KamanjaConfiguration.zkSessionTimeoutMs = if (KamanjaConfiguration.zkSessionTimeoutMs <= 0) 30000 else KamanjaConfiguration.zkSessionTimeoutMs
    KamanjaConfiguration.zkConnectionTimeoutMs = if (KamanjaConfiguration.zkConnectionTimeoutMs <= 0) 30000 else KamanjaConfiguration.zkConnectionTimeoutMs

    return true
  }

  def ValidateAllRequiredJars: Boolean = {
    val allJarsToBeValidated = scala.collection.mutable.Set[String]();

    // EnvContext Jars
    val cluster = mdMgr.ClusterCfgs.getOrElse(KamanjaConfiguration.clusterId, null)
    if (cluster == null) {
      LOG.error("Cluster not found for Node %d  & ClusterId : %s".format(KamanjaConfiguration.nodeId, KamanjaConfiguration.clusterId))
      return false
    }

    val envCtxtStr = cluster.cfgMap.getOrElse("EnvironmentContext", null)
    if (envCtxtStr == null) {
      LOG.error("EnvironmentContext string not found for Node %d  & ClusterId : %s".format(KamanjaConfiguration.nodeId, KamanjaConfiguration.clusterId))
      return false
    }

    implicit val jsonFormats: Formats = DefaultFormats
    val evnCtxtJson = parse(envCtxtStr).extract[JEnvCtxtJsonStr]

    val jarName = evnCtxtJson.jarname.replace("\"", "").trim
    val dependencyJars = if (evnCtxtJson.dependencyjars == None || evnCtxtJson.dependencyjars == null) null else evnCtxtJson.dependencyjars.get.map(str => str.replace("\"", "").trim).filter(str => str.size > 0).toSet
    var allJars: collection.immutable.Set[String] = null

    if (dependencyJars != null && jarName != null) {
      allJars = dependencyJars + jarName
    } else if (dependencyJars != null) {
      allJars = dependencyJars
    } else if (jarName != null) {
      allJars = collection.immutable.Set(jarName)
    }

    if (allJars != null) {
      allJarsToBeValidated ++= allJars.map(j => Utils.GetValidJarFile(KamanjaConfiguration.jarPaths, j))
    }

    // All Adapters
    val allAdapters = mdMgr.Adapters

    allAdapters.foreach(a => {
      if ((a._2.TypeString.compareToIgnoreCase("Input") == 0) ||
        (a._2.TypeString.compareToIgnoreCase("Validate") == 0) ||
        (a._2.TypeString.compareToIgnoreCase("Output") == 0) ||
        (a._2.TypeString.compareToIgnoreCase("Status") == 0)) {
        val jar = a._2.JarName
        val depJars = if (a._2.DependencyJars != null) a._2.DependencyJars.map(str => str.trim).filter(str => str.size > 0).toSet else null

        if (jar != null && jar.size > 0) {
          allJarsToBeValidated += Utils.GetValidJarFile(KamanjaConfiguration.jarPaths, jar)
        }
        if (depJars != null && depJars.size > 0) {
          allJarsToBeValidated ++= depJars.map(j => Utils.GetValidJarFile(KamanjaConfiguration.jarPaths, j))
        }
      } else {
        LOG.error("Found unhandled adapter type %s for adapter %s".format(a._2.TypeString, a._2.Name))
        return false
      }
    })

    val nonExistsJars = Utils.CheckForNonExistanceJars(allJarsToBeValidated.toSet)
    if (nonExistsJars.size > 0) {
      LOG.error("Not found jars in EnvContext and/or Adapters Jars List : {" + nonExistsJars.mkString(", ") + "}")
      return false
    }

    true
  }

  def LoadEnvCtxt(loaderInfo: KamanjaLoaderInfo): EnvContext = {
    val cluster = mdMgr.ClusterCfgs.getOrElse(KamanjaConfiguration.clusterId, null)
    if (cluster == null) {
      LOG.error("Cluster not found for Node %d  & ClusterId : %s".format(KamanjaConfiguration.nodeId, KamanjaConfiguration.clusterId))
      return null
    }

    val envCtxt1 = cluster.cfgMap.getOrElse("EnvironmentContextInfo", null)
    val envCtxtStr = if (envCtxt1 == null) cluster.cfgMap.getOrElse("EnvironmentContext", null) else envCtxt1
    if (envCtxtStr == null) {
      LOG.error("EnvironmentContext string not found for Node %d  & ClusterId : %s".format(KamanjaConfiguration.nodeId, KamanjaConfiguration.clusterId))
      return null
    }

    implicit val jsonFormats: Formats = DefaultFormats
    val evnCtxtJson = parse(envCtxtStr).extract[JEnvCtxtJsonStr]

    //BUGBUG:: Not yet validating required fields 
    val className = evnCtxtJson.classname.replace("\"", "").trim
    val jarName = evnCtxtJson.jarname.replace("\"", "").trim
    val dependencyJars = if (evnCtxtJson.dependencyjars == None || evnCtxtJson.dependencyjars == null) null else evnCtxtJson.dependencyjars.get.map(str => str.replace("\"", "").trim).filter(str => str.size > 0).toSet
    var allJars: collection.immutable.Set[String] = null

    if (dependencyJars != null && jarName != null) {
      allJars = dependencyJars + jarName
    } else if (dependencyJars != null) {
      allJars = dependencyJars
    } else if (jarName != null) {
      allJars = collection.immutable.Set(jarName)
    }

    if (allJars != null) {
      if (Utils.LoadJars(allJars.map(j => Utils.GetValidJarFile(KamanjaConfiguration.jarPaths, j)).toArray, loaderInfo.loadedJars, loaderInfo.loader) == false)
        throw new Exception("Failed to add Jars")
    }

    // Convert class name into a class
    val clz = Class.forName(className, true, loaderInfo.loader)

    var isEntCtxt = false
    var curClz = clz

    while (clz != null && isEntCtxt == false) {
      isEntCtxt = Utils.isDerivedFrom(curClz, "com.ligadata.KamanjaBase.EnvContext")
      if (isEntCtxt == false)
        curClz = curClz.getSuperclass()
    }

    if (isEntCtxt) {
      try {
        val module = loaderInfo.mirror.staticModule(className)
        val obj = loaderInfo.mirror.reflectModule(module)

        val objinst = obj.instance
        if (objinst.isInstanceOf[EnvContext]) {
          val envCtxt = objinst.asInstanceOf[EnvContext]
          envCtxt.SetClassLoader(loaderInfo.loader)
          envCtxt.SetMetadataResolveInfo(KamanjaMetadata)
          envCtxt.setMdMgr(KamanjaMetadata.getMdMgr)
          val containerNames = KamanjaMetadata.getAllContainers.map(container => container._1.toLowerCase).toList.sorted.toArray // Sort topics by names
          val topMessageNames = KamanjaMetadata.getAllMessges.filter(msg => msg._2.parents.size == 0).map(msg => msg._1.toLowerCase).toList.sorted.toArray // Sort topics by names
          envCtxt.AddNewMessageOrContainers(KamanjaConfiguration.dataDataStoreInfo, containerNames, true, KamanjaConfiguration.statusDataStoreInfo, KamanjaConfiguration.jarPaths) // Containers
          envCtxt.AddNewMessageOrContainers(KamanjaConfiguration.dataDataStoreInfo, topMessageNames, false, KamanjaConfiguration.statusDataStoreInfo, KamanjaConfiguration.jarPaths) // Messages
          LOG.info("Created EnvironmentContext for Class:" + className)
          return envCtxt
        } else {
          LOG.error("Failed to instantiate Environment Context object for Class:" + className + ". ObjType0:" + objinst.getClass.getSimpleName + ". ObjType1:" + objinst.getClass.getCanonicalName)
        }
      } catch {
        case e: Exception => {
          LOG.error("Failed to instantiate Environment Context object for Class:" + className + ". Reason:" + e.getCause + ". Message:" + e.getMessage)
          
        }
      }
    } else {
      LOG.error("Failed to instantiate Environment Context object for Class:" + className)
    }
    null
  }

  def LoadAdapters(loaderInfo: KamanjaLoaderInfo, inputAdapters: ArrayBuffer[InputAdapter], outputAdapters: ArrayBuffer[OutputAdapter], statusAdapters: ArrayBuffer[OutputAdapter], validateInputAdapters: ArrayBuffer[InputAdapter]): Boolean = {
    LOG.info("Loading Adapters started @ " + Utils.GetCurDtTmStr)
    val s0 = System.nanoTime

    val allAdapters = mdMgr.Adapters

    val inputAdaps = scala.collection.mutable.Map[String, AdapterInfo]()
    val validateAdaps = scala.collection.mutable.Map[String, AdapterInfo]()
    val outputAdaps = scala.collection.mutable.Map[String, AdapterInfo]()
    val statusAdaps = scala.collection.mutable.Map[String, AdapterInfo]()

    allAdapters.foreach(a => {
      if (a._2.TypeString.compareToIgnoreCase("Input") == 0) {
        inputAdaps(a._1.toLowerCase) = a._2
      } else if (a._2.TypeString.compareToIgnoreCase("Validate") == 0) {
        validateAdaps(a._1.toLowerCase) = a._2
      } else if (a._2.TypeString.compareToIgnoreCase("Output") == 0) {
        outputAdaps(a._1.toLowerCase) = a._2
      } else if (a._2.TypeString.compareToIgnoreCase("Status") == 0) {
        statusAdaps(a._1.toLowerCase) = a._2
      } else {
        LOG.error("Found unhandled adapter type %s for adapter %s".format(a._2.TypeString, a._2.Name))
        return false
      }
    })

    // Get status adapter
    LOG.debug("Getting Status Adapter")

    if (LoadOutputAdapsForCfg(statusAdaps, statusAdapters, loaderInfo, false) == false)
      return false

    // Get output adapter
    LOG.debug("Getting Output Adapters")

    if (LoadOutputAdapsForCfg(outputAdaps, outputAdapters, loaderInfo, true) == false)
      return false

    // Get input adapter
    LOG.debug("Getting Input Adapters")

    if (LoadInputAdapsForCfg(inputAdaps, inputAdapters, outputAdapters.toArray, KamanjaMetadata.envCtxt, loaderInfo) == false)
      return false

    // Get input adapter
    LOG.debug("Getting Validate Input Adapters")

    if (LoadValidateInputAdapsFromCfg(validateAdaps, validateInputAdapters, outputAdapters.toArray, KamanjaMetadata.envCtxt, loaderInfo) == false)
      return false

    val totaltm = "TimeConsumed:%.02fms".format((System.nanoTime - s0) / 1000000.0);
    LOG.info("Loading Adapters done @ " + Utils.GetCurDtTmStr + totaltm)

    true
  }

  private def CreateOutputAdapterFromConfig(statusAdapterCfg: AdapterConfiguration, loaderInfo: KamanjaLoaderInfo): OutputAdapter = {
    if (statusAdapterCfg == null) return null
    var allJars: collection.immutable.Set[String] = null
    if (statusAdapterCfg.dependencyJars != null && statusAdapterCfg.jarName != null) {
      allJars = statusAdapterCfg.dependencyJars + statusAdapterCfg.jarName
    } else if (statusAdapterCfg.dependencyJars != null) {
      allJars = statusAdapterCfg.dependencyJars
    } else if (statusAdapterCfg.jarName != null) {
      allJars = collection.immutable.Set(statusAdapterCfg.jarName)
    }

    if (allJars != null) {
      if (Utils.LoadJars(allJars.map(j => Utils.GetValidJarFile(KamanjaConfiguration.jarPaths, j)).toArray, loaderInfo.loadedJars, loaderInfo.loader) == false)
        throw new Exception("Failed to add Jars")
    }

    // Convert class name into a class
    val clz = Class.forName(statusAdapterCfg.className, true, loaderInfo.loader)

    var isOutputAdapter = false
    var curClz = clz

    while (clz != null && isOutputAdapter == false) {
      isOutputAdapter = Utils.isDerivedFrom(curClz, "com.ligadata.InputOutputAdapterInfo.OutputAdapterObj")
      if (isOutputAdapter == false)
        curClz = curClz.getSuperclass()
    }

    if (isOutputAdapter) {
      try {
        val module = loaderInfo.mirror.staticModule(statusAdapterCfg.className)
        val obj = loaderInfo.mirror.reflectModule(module)

        val objinst = obj.instance
        if (objinst.isInstanceOf[OutputAdapterObj]) {
          val adapterObj = objinst.asInstanceOf[OutputAdapterObj]
          val adapter = adapterObj.CreateOutputAdapter(statusAdapterCfg, SimpleStats)
          LOG.info("Created Output Adapter for Name:" + statusAdapterCfg.Name + ", Class:" + statusAdapterCfg.className)
          return adapter
        } else {
          LOG.error("Failed to instantiate output adapter object:" + statusAdapterCfg.className)
        }
      } catch {
        case e: Exception => {
          LOG.error("Failed to instantiate output adapter object:" + statusAdapterCfg.className + ". Reason:" + e.getCause + ". Message:" + e.getMessage)}
      }
    } else {
      LOG.error("Failed to instantiate output adapter object:" + statusAdapterCfg.className)
    }
    null
  }

  private def LoadOutputAdapsForCfg(adaps: scala.collection.mutable.Map[String, AdapterInfo], outputAdapters: ArrayBuffer[OutputAdapter], loaderInfo: KamanjaLoaderInfo, hasInputAdapterName: Boolean): Boolean = {
    // ConfigurationName
    if (adaps.size > 1) {
      LOG.error(" Got %d ouput adapters, but we are expecting only one output adapter.".format(adaps.size))
      return false
    }
    adaps.foreach(ac => {
      //BUGBUG:: Not yet validating required fields 
      val conf = new AdapterConfiguration

      val adap = ac._2

      conf.Name = adap.Name.toLowerCase
      if (hasInputAdapterName)
        conf.formatOrInputAdapterName = adap.InputAdapterToVerify
      conf.className = adap.ClassName
      conf.jarName = adap.JarName
      conf.delimiterString = adap.DelimiterString
      conf.associatedMsg = adap.AssociatedMessage
      conf.dependencyJars = if (adap.DependencyJars != null) adap.DependencyJars.map(str => str.trim).filter(str => str.size > 0).toSet else null
      conf.adapterSpecificCfg = adap.AdapterSpecificCfg

      try {
        val adapter = CreateOutputAdapterFromConfig(conf, loaderInfo)
        if (adapter == null) return false
        outputAdapters += adapter
      } catch {
        case e: Exception =>{
          LOG.error("Failed to get output adapter for %s. Reason:%s Message:%s".format(ac, e.getCause, e.getMessage))
          return false
          }
      }
    })
    return true
  }

  private def CreateInputAdapterFromConfig(statusAdapterCfg: AdapterConfiguration, callerCtxt: InputAdapterCallerContext, loaderInfo: KamanjaLoaderInfo, execCtxtObj: ExecContextObj): InputAdapter = {
    if (statusAdapterCfg == null) return null
    var allJars: collection.immutable.Set[String] = null

    if (statusAdapterCfg.dependencyJars != null && statusAdapterCfg.jarName != null) {
      allJars = statusAdapterCfg.dependencyJars + statusAdapterCfg.jarName
    } else if (statusAdapterCfg.dependencyJars != null) {
      allJars = statusAdapterCfg.dependencyJars
    } else if (statusAdapterCfg.jarName != null) {
      allJars = collection.immutable.Set(statusAdapterCfg.jarName)
    }

    if (allJars != null) {
      if (Utils.LoadJars(allJars.map(j => Utils.GetValidJarFile(KamanjaConfiguration.jarPaths, j)).toArray, loaderInfo.loadedJars, loaderInfo.loader) == false)
        throw new Exception("Failed to add Jars")
    }

    // Convert class name into a class
    val clz = Class.forName(statusAdapterCfg.className, true, loaderInfo.loader)

    var isInputAdapter = false
    var curClz = clz

    while (clz != null && isInputAdapter == false) {
      isInputAdapter = Utils.isDerivedFrom(curClz, "com.ligadata.InputOutputAdapterInfo.InputAdapterObj")
      if (isInputAdapter == false)
        curClz = curClz.getSuperclass()
    }

    if (isInputAdapter) {
      try {
        val module = loaderInfo.mirror.staticModule(statusAdapterCfg.className)
        val obj = loaderInfo.mirror.reflectModule(module)

        val objinst = obj.instance
        if (objinst.isInstanceOf[InputAdapterObj]) {
          val adapterObj = objinst.asInstanceOf[InputAdapterObj]
          val adapter = adapterObj.CreateInputAdapter(statusAdapterCfg, callerCtxt, execCtxtObj, SimpleStats)
          LOG.info("Created Input Adapter for Name:" + statusAdapterCfg.Name + ", Class:" + statusAdapterCfg.className)
          return adapter
        } else {
          LOG.error("Failed to instantiate input adapter object:" + statusAdapterCfg.className)
        }
      } catch {
        case e: Exception =>{ 
          LOG.error("Failed to instantiate input adapter object:" + statusAdapterCfg.className + ". Reason:" + e.getCause + ". Message:" + e.getMessage)}
      }
    } else {
      LOG.error("Failed to instantiate input adapter object:" + statusAdapterCfg.className)
    }
    null
  }

  private def PrepInputAdapsForCfg(adaps: scala.collection.mutable.Map[String, AdapterInfo], inputAdapters: ArrayBuffer[InputAdapter], outputAdapters: Array[OutputAdapter], envCtxt: EnvContext, loaderInfo: KamanjaLoaderInfo, execCtxtObj: ExecContextObj): Boolean = {
    // ConfigurationName
    if (adaps.size == 0) {
      return true
    }

    val callerCtxt = new KamanjaInputAdapterCallerContext
    callerCtxt.outputAdapters = outputAdapters
    callerCtxt.envCtxt = envCtxt

    adaps.foreach(ac => {
      //BUGBUG:: Not yet validating required fields 
      val conf = new AdapterConfiguration

      val adap = ac._2

      conf.Name = adap.Name.toLowerCase
      conf.formatOrInputAdapterName = adap.DataFormat
      conf.className = adap.ClassName
      conf.jarName = adap.JarName
      conf.dependencyJars = if (adap.DependencyJars != null) adap.DependencyJars.map(str => str.trim).filter(str => str.size > 0).toSet else null
      conf.adapterSpecificCfg = adap.AdapterSpecificCfg
      conf.delimiterString = adap.DelimiterString
      conf.associatedMsg = adap.AssociatedMessage

      try {
        val adapter = CreateInputAdapterFromConfig(conf, callerCtxt, loaderInfo, execCtxtObj)
        if (adapter == null) return false
        inputAdapters += adapter
      } catch {
        case e: Exception =>{
          LOG.error("Failed to get input adapter for %s. Reason:%s Message:%s".format(ac, e.getCause, e.getMessage))
          return false
        }
      }
    })
    return true
  }

  private def LoadInputAdapsForCfg(adaps: scala.collection.mutable.Map[String, AdapterInfo], inputAdapters: ArrayBuffer[InputAdapter], outputAdapters: Array[OutputAdapter], envCtxt: EnvContext, loaderInfo: KamanjaLoaderInfo): Boolean = {
    return PrepInputAdapsForCfg(adaps, inputAdapters, outputAdapters, envCtxt, loaderInfo, ExecContextObjImpl)
  }

  private def LoadValidateInputAdapsFromCfg(validate_adaps: scala.collection.mutable.Map[String, AdapterInfo], valInputAdapters: ArrayBuffer[InputAdapter], outputAdapters: Array[OutputAdapter], envCtxt: EnvContext, loaderInfo: KamanjaLoaderInfo): Boolean = {
    val validateInputAdapters = scala.collection.mutable.Map[String, AdapterInfo]()

    outputAdapters.foreach(oa => {
      val validateInputAdapName = (if (oa.inputConfig.formatOrInputAdapterName != null) oa.inputConfig.formatOrInputAdapterName.trim else "").toLowerCase
      if (validateInputAdapName.size > 0) {
        val valAdap = validate_adaps.getOrElse(validateInputAdapName, null)
        if (valAdap != null) {
          validateInputAdapters(validateInputAdapName) = valAdap
        } else {
          LOG.warn("Not found validate input adapter %s for %s".format(validateInputAdapName, oa.inputConfig.Name))
        }
      } else {
        LOG.warn("Not found validate input adapter for " + oa.inputConfig.Name)
      }
    })
    if (validateInputAdapters.size == 0)
      return true
    return PrepInputAdapsForCfg(validateInputAdapters, valInputAdapters, outputAdapters, envCtxt, loaderInfo, ValidateExecContextObjImpl)
  }

}
