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

package com.ligadata.MetadataAPI.Utility

import java.io.File

import com.ligadata.MetadataAPI.MetadataAPIImpl

import scala.io.Source
import org.apache.logging.log4j._

import scala.io.StdIn

/**
 * Created by dhaval on 8/13/15.
 */
object ConfigService {
  private val userid: Option[String] = Some("metadataapi")
  val loggerName = this.getClass.getName
  lazy val logger = LogManager.getLogger(loggerName)

 def uploadClusterConfig(input: String): String ={
   var response = ""
   var configFileDir: String = ""
   //val gitMsgFile = "https://raw.githubusercontent.com/ligadata-dhaval/Kamanja/master/HelloWorld_Msg_Def.json"
   if (input == "") {
     configFileDir = MetadataAPIImpl.GetMetadataAPIConfig.getProperty("CONFIG_FILES_DIR")
     if (configFileDir == null) {
       response = "CONFIG_FILES_DIR property missing in the metadata API configuration"
     } else {
       //verify the directory where messages can be present
       IsValidDir(configFileDir) match {
         case true => {
           //get all files with json extension
           val types: Array[File] = new java.io.File(configFileDir).listFiles.filter(_.getName.endsWith(".json"))
           types.length match {
             case 0 => {
               println("Configs not found at " + configFileDir)
               response="Configs not found at " + configFileDir
             }
             case option => {
               val configDefs = getUserInputFromMainMenu(types)
               for (configDef <- configDefs) {
                 response += MetadataAPIImpl.UploadConfig(configDef.toString, userid, "configuration")
               }
             }
           }
         }
         case false => {
           //println("Message directory is invalid.")
           response = "Config directory is invalid."
         }
       }
     }
   } else {
     //input provided
     var message = new File(input.toString)
     val configDef = Source.fromFile(message).mkString
     response = MetadataAPIImpl.UploadConfig(configDef.toString, userid, "configuration")
   }
   response
 }

  def uploadCompileConfig(input: String): String ={
    var response = ""
    var configFileDir: String = ""
    //val gitMsgFile = "https://raw.githubusercontent.com/ligadata-dhaval/Kamanja/master/HelloWorld_Msg_Def.json"
    if (input == "") {
      configFileDir = MetadataAPIImpl.GetMetadataAPIConfig.getProperty("CONFIG_FILES_DIR")
      if (configFileDir == null) {
        response = "CONFIG_FILES_DIR property missing in the metadata API configuration"
      } else {
        //verify the directory where messages can be present
        IsValidDir(configFileDir) match {
          case true => {
            //get all files with json extension
            val types: Array[File] = new java.io.File(configFileDir).listFiles.filter(_.getName.endsWith(".json"))
            types.length match {
              case 0 => {
                println("Configs not found at " + configFileDir)
                response="Configs not found at " + configFileDir
              }
              case option => {
                val configDefs = getUserInputFromMainMenu(types)
                for (configDef <- configDefs) {
                  response += MetadataAPIImpl.UploadModelsConfig(configDef.toString, userid, "configuration")
                }
              }
            }
          }
          case false => {
            //println("Message directory is invalid.")
            response = "Config directory is invalid."
          }
        }
      }
    } else {
      //input provided
      var message = new File(input.toString)
      val configDef = Source.fromFile(message).mkString
      response = MetadataAPIImpl.UploadModelsConfig(configDef.toString, userid, "configuration")
    }
    response
  }
  def dumpAllCfgObjects: String ={
    var response=""
    try{
      response= MetadataAPIImpl.GetAllCfgObjects("JSON", userid)
    }
    catch {
      case e: Exception => {
        response=e.getStackTrace.toString
      }
    }
    response

  }
  def removeEngineConfig: String ={
    var response="TO BE IMPLEMENTED"
    response
  }

  def IsValidDir(dirName: String): Boolean = {
    val iFile = new File(dirName)
    if (!iFile.exists) {
      println("The File Path (" + dirName + ") is not found: ")
      false
    } else if (!iFile.isDirectory) {
      println("The File Path (" + dirName + ") is not a directory: ")
      false
    } else
      true
  }

  def   getUserInputFromMainMenu(messages: Array[File]): Array[String] = {
    var listOfMsgDef: Array[String] = Array[String]()
    var srNo = 0
    println("\nPick a Config Definition file(s) from below choices\n")
    for (message <- messages) {
      srNo += 1
      println("[" + srNo + "]" + message)
    }
    print("\nEnter your choice(If more than 1 choice, please use commas to seperate them): \n")
    val userOptions: List[Int] = StdIn.readLine().filter(_ != '\n').split(',').filter(ch => (ch != null && ch != "")).map(_.trim.toInt).toList
    //check if user input valid. If not exit
    for (userOption <- userOptions) {
      userOption match {
        case userOption if (1 to srNo).contains(userOption) => {
          //find the file location corresponding to the message
          var message = messages(userOption - 1)
          //process message
          val messageDef = Source.fromFile(message).mkString
          listOfMsgDef = listOfMsgDef :+ messageDef
        }
        case _ => {
          println("Unknown option: ")
        }
      }
    }
    listOfMsgDef
  }
}
