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
package com.ligadata.jtm

import com.ligadata.kamanja.metadata.{StructTypeDef, MdMgr}
import com.ligadata.kamanja.metadataload.MetadataLoad
import com.ligadata.messagedef.MessageDefImpl
import org.apache.logging.log4j.{ Logger, LogManager }
import org.json4s.jackson.JsonMethods._
import org.rogach.scallop._
import org.apache.commons.io.FileUtils
import java.io.File

import com.ligadata.jtm.nodes._

object jtmGlobalLogger {
  val loggerName = this.getClass.getName()
  val logger = LogManager.getLogger(loggerName)
}

trait LogTrait {
  val logger = jtmGlobalLogger.logger
}

class Conf (arguments: Seq[String] ) extends ScallopConf (arguments)  with LogTrait {

  val jtm = opt[String] (required = true, descr = "Sources to compile", default = None )
/*
  val scalahome = opt[String] (required = false, descr = "", default = Some ("") )
  val javahome = opt[String] (required = false, descr = "", default = Some ("") )
  val cp = opt[String] (required = false, descr = "", default = Some ("") )
  val jarpath = opt[String] (required = false, descr = "", default = Some ("") )
  val scriptout = opt[String] (required = false, descr = "Sources to compile", default = Some ("") )
  val manifest = opt[String] (required = false, descr = "Sources to compile", default = Some ("") )
  val client = opt[String] (required = false, descr = "Sources to compile", default = Some ("") )
  val sourceout = opt[String] (required = false, descr = "Path to the location to store generated sources", default = Some ("") )
  val addLogging = opt[Boolean] (required = false, descr = "Add logging code to the model", default = Some (true) )
  val createjar = opt[Boolean] (required = false, descr = "Create the final jar output ", default = Some (true) )
*/
}

/* Commandline interface to compiler
 *
 */
object Compiler extends App with LogTrait {
  override def main (args: Array[String] ) {

      try {
        val cmdconf = new Conf(args)
      }
      catch {
        case e: Exception => {
          System.exit(1)
        }
      }
      // Do all validations

      // Create compiler instance and generate scala code
  }
}

object CompilerBuilder {
  def create() = { new CompilerBuilder }
}

class CompilerBuilder {

  def setSuppressTimestamps(switch: Boolean = true) = { suppressTimestamps = switch; this}
  def setInputFile(filename: String) = { inputFile = filename; this }
  def setOutputFile(filename: String) = { outputFile = filename; this }
  def setMetadataLocation(filename: String) = { metadataLocation = filename; this }

  var inputFile : String = null
  var outputFile : String = null
  var metadataLocation : String = null

  var suppressTimestamps : Boolean = false

  def build() : Compiler = {
    new Compiler(this)
  }
}

/* Translates a jtm (json) file(s) into scala classes
 *
 */
class Compiler(params: CompilerBuilder) extends LogTrait {

  def splitPackageClass(name: String): (String, String) = {
    val elements = name.split('.')
    (elements.dropRight(1).mkString("."), elements.last)
  }

  def loadMetadata() = {

    val typesPath : String = ""
    val fcnPath : String = ""
    val attrPath : String = ""
    val msgCtnPath : String = ""
    val mgr : MdMgr = MdMgr.GetMdMgr

    val mdLoader = new MetadataLoad (mgr, typesPath, fcnPath, attrPath, msgCtnPath)
    mdLoader.initialize

    val jsonFile = params.metadataLocation +  "/messages/message_type.json"
    val json = FileUtils.readFileToString(new File(jsonFile), null)
    val map = parse(json).values.asInstanceOf[Map[String, Any]]

    val msg = new MessageDefImpl()
    val ((classStrVer, classStrVerJava), msgDef, (classStrNoVer, classStrNoVerJava)) = msg.processMsgDef(json, "JSON", mgr, false)
    val msg1 = msgDef.asInstanceOf[com.ligadata.kamanja.metadata.MessageDef]
    mgr.AddMsg(msg1)
    mgr
  }

  // Load metadata
  //val md = loadMetadata

  //md.dump
  //val m = md.Message("com.ligadata.kamanja.samples.messages.msg1", 0, true)
  //logger.trace("Found: {}", m.toString())

  val suppressTimestamps: Boolean = params.suppressTimestamps // Suppress timestamps
  val inputFile: String = params.inputFile // Input file to compile
  val outputFile: String = params.outputFile // Output file to write

  def CollectInputs(t: Array[Transformation]): Array[String] = {
    val s = t.map( e => {
      e.input
    })
    s.toSet.toArray
  }

  def CollectOutputs(t: Array[Transformation]): Array[String] = {
    val s = t.foldLeft(Array.empty[String]) ( (r, e) => {
      val s = e.outputs.foldLeft(r) ( (r, e) => {
        r ++ Array(e.output)
      })
      r ++ s
    })
    s.toSet.toArray
  }

  //
  //
  def ConstructIsValidMessage(inputTypes: Array[String]): String = {
    inputTypes.map( e => "    msg.isInstanceOf[%s]".format(e) ).mkString("||\n") + "\n"
  }

  def ExtractColumnNames(expression: String): Set[String] = {
    val regex = """(\$[a-zA-Z0-9_]+)""".r
    regex.findAllMatchIn(expression).toArray.map( m => m.matched.drop(1)).toSet
  }

  def FixupColumnNames(expression: String, mapNameSource : Map[String, String]): String = {
    val regex = """(\$[a-zA-Z0-9_]+)""".r
    val m = regex.pattern.matcher(expression)
    val sb = new StringBuffer
    var i = 0;
    while (m.find) {
      m.appendReplacement(sb, mapNameSource.get(m.group(0).drop(1)).get)
      i = i + 1
    }
    m.appendTail(sb)
    sb.toString
  }

  def Execute(): String = {

    // Load Json
    val root = Root.fromJson(inputFile)

    val sb = new StringBuilder
    sb.append(Parts.header)
    sb.append("\n")
    sb.append("package %s\n".format(root.packagename))

    // Push substituions
    var subtitutions = new Substitution
    subtitutions.Add("model.name", root.modelname)
    subtitutions.Add("model.version", root.version)

    val imports = subtitutions.Run(Parts.imports)
    sb.append(imports)
    sb.append("\n\n")

    root.imports.map( i => { sb.append("import %s\n".format(i)) })


    // Check Inputs
    //
    //<TBD>

    // Check outputs
    //
    //<TBD>

    case class leg(val packagename: String, val classname: String, val handle: String, val id: Int)

    // Constructs the input and output types
    val inputMap = CollectInputs(root.transformations).zipWithIndex.map( e => {
      val (packagename, classname) = splitPackageClass(e._1)
      val handle = "mi%d".format(e._2)
      ( e._1 -> leg(packagename, classname, handle, e._2))
    })

    val outputMap = CollectOutputs(root.transformations).zipWithIndex.map( e => {
      val (packagename, classname) = splitPackageClass(e._1)
      val handle = "mo%d".format(e._2)
      ( e._1 -> leg(packagename, classname, handle, e._2))
    })

    val inputs = inputMap.map( p => {
        val leg = p._2
        "import %s.{%s ⇒ %s}".format(leg.packagename, leg.classname, leg.handle)
    }).mkString("\n")
    sb.append(inputs)
    sb.append("\n")

    val outputs = outputMap.map( p => {
      val leg = p._2
      "import %s.{%s ⇒ %s}".format(leg.packagename, leg.classname, leg.handle)
    }).mkString("\n")
    sb.append(outputs)
    sb.append("\n")

    subtitutions.Add("factory.isvalidmessage", ConstructIsValidMessage(inputMap.map( p => p._2.handle )))
    val factory = subtitutions.Run(Parts.factory)
    sb.append(factory)
    sb.append("\n\n")

    // Collect all outputs here
    val resultVar = "    var result: Array[Result] = Array.empty[Result]"

    val inputprocessing = inputMap.map( p => {
      val leg = p._2

      // Get all transformations attached to the input
      val transformations = root.transformations.filter( t => t.input == p._1 )

      val transparts = transformations.foldLeft(Array.empty[Array[String]]) ( (r, t) => {

        var tr = Array("\n{")

        // Process the output per message

        // variables
        tr ++= t.variables.map( e => "val %s: %s".format(e.name, e.typename))

        tr ++= t.outputs.foldLeft(Array.empty[String]) ( (r, o) => {

          var collect = Array.empty[String]
          collect ++= Array("{\n")
          collect ++= Array("val filtered: Boolean = false\n")
          collect ++= Array("val msg = msg.isInstanceOf[%s]\n".format(leg.handle))

          // Collect form metadata
          var mapNameSource : Map[String, String] = Map(("in1" -> "msg.in1"), ("in2" -> "msg.in2"), ("in3" -> "msg.in3"))

          var outputSet : Set[String] = Seq("out1", "out2", "out3", "out4").toSet
          val outputSet1 : Set[String] = Seq("out1", "out2", "out3", "out4").toSet
          var mapping = o.mappings
          var filters =  o.filters
          var computes = o.computes
          var cnt1 = filters.length + computes.length
          var cnt2 = 0

          // Removed if mappings are provided
          val found = mapping.filter( f => mapNameSource.contains(f._2) )
          found.foreach(f => { outputSet --= Set(f._1); mapNameSource ++= Map(f._1 -> mapNameSource.get(f._2).get) })
          mapping = mapping.filterKeys( f => !found.contains(f)  )

          // Abort this loop if nothing changes or we can satisfy all outputs
          while(cnt1!=cnt2 && outputSet.size > 0) {

            cnt2 = cnt1

            // filters
            val filters1 = filters.filter(f => {
              val list = ExtractColumnNames(f.expression)
              val open = list.filter(f => !mapNameSource.contains(f) )
              if(open.size==0) {
                // Sub names to
                val newExpression = FixupColumnNames(f.expression, mapNameSource)
                // Output the actual filter
                collect ++= Array("if (!filtered) {\nfilter = %s\n}\n".format(newExpression))
                false
              } else {
                true
              }
            })

            // computes
            val computes1 = computes.filter( c => {
              val list = ExtractColumnNames(c.expression)
              val open = list.filter(f => !mapNameSource.contains(f) )
              if(open.size==0) {
                // Sub names to
                val newExpression = FixupColumnNames(c.expression, mapNameSource)
                // Output the actual compute
                collect ++= Array("if (!filtered) {\nval %s = %s\n}\n".format(c.output, newExpression))
                mapNameSource ++= Map(c.output -> c.output)
                outputSet --= Set(c.output)
                false
              } else {
                true
              }
            })

            // Check Mapping
            if(mapping.size>0)
            {
              val found = mapping.filter( f => mapNameSource.contains(f._2) )
              found.foreach(f => {outputSet --= Set(f._1); mapNameSource ++= Map(f._1 -> mapNameSource.get(f._2).get)})
              mapping = mapping.filterKeys( f => !found.contains(f)  )
            }

            // Update state
            cnt1 = filters1.length + computes1.length
            filters = filters1
            computes = computes1
          }

          if(outputSet.size>0){
            throw new Exception("Not all outputs satisfied. missing=" + outputSet.mkString(", "))
            logger.trace("Not all outputs satisfied. missing={}" , outputSet.mkString(", "))
          }

          if(cnt2!=0){
            //throw new Exception("Not all elements used")
            logger.trace("Not all elements used")
          }

          // Generate the output for this iteration
          // Translate outputs to the values
          val outputElements = outputSet1.map( e => {
            // e.name -> from input, from mapping, from variable
            "new Result(\"%s\", %s)".format(e, mapNameSource.get(e).get)
          }).mkString(", ")
          val outputResult = "if (!filtered) {\nresult ++= Array[Result](%s)\n}\n".format(outputElements)

          collect ++= Array(outputResult)
          collect ++= Array("}\n")

          // outputs
          r ++ collect
        })

        tr ++= Array("}\n")

        r ++ Array(tr)
      })

      "if(msg.isInstanceOf[%s]) {".format(leg.handle) +
      transparts.map( e => e.mkString("\n") + "\n").mkString("\n") + "\n" +
      "}"
    })

    val returnValue = "factory.createResultObject().asInstanceOf[MappedModelResults].withResults(result)"

    subtitutions.Add("model.methods", "")
    subtitutions.Add("model.code", resultVar + "\n\n" + inputprocessing.mkString("\n") + "\n\n" + returnValue + "\n")
    val model = subtitutions.Run(Parts.model)
    sb.append(model)
    sb.append("\n")

/*
    // Read the output type information
    val output = md.Message(root.inputs(0).typename, 0, true)

    // Read input type information
    val input = md.Message(root.outputs(0).typename, 0, true)
*/
    // Write to output file
    logger.trace("Output to file {}", outputFile)
    FileUtils.writeStringToFile(new File(outputFile), CodeHelper.Indent(sb.result))

    outputFile
  }
}