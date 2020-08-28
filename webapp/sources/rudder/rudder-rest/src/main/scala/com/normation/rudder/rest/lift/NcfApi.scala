/*
*************************************************************************************
* Copyright 2017 Normation SAS
*************************************************************************************
*
* This file is part of Rudder.
*
* Rudder is free software: you can redistribute it and/or modify
* it under the terms of the GNU General Public License as published by
* the Free Software Foundation, either version 3 of the License, or
* (at your option) any later version.
*
* In accordance with the terms of section 7 (7. Additional Terms.) of
* the GNU General Public License version 3, the copyright holders add
* the following Additional permissions:
* Notwithstanding to the terms of section 5 (5. Conveying Modified Source
* Versions) and 6 (6. Conveying Non-Source Forms.) of the GNU General
* Public License version 3, when you create a Related Module, this
* Related Module is not considered as a part of the work and may be
* distributed under the license agreement of your choice.
* A "Related Module" means a set of sources files including their
* documentation that, without modification of the Source Code, enables
* supplementary functions or services in addition to those offered by
* the Software.
*
* Rudder is distributed in the hope that it will be useful,
* but WITHOUT ANY WARRANTY; without even the implied warranty of
* MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
* GNU General Public License for more details.
*
* You should have received a copy of the GNU General Public License
* along with Rudder.  If not, see <http://www.gnu.org/licenses/>.

*
*************************************************************************************
*/

package com.normation.rudder.rest.lift

import better.files.File
import com.normation.box._
import com.normation.cfclerk.domain.TechniqueName
import com.normation.cfclerk.services.GitRepositoryProvider
import com.normation.cfclerk.services.TechniqueRepository
import com.normation.errors.IOResult
import com.normation.errors.Inconsistency
import com.normation.eventlog.EventActor
import com.normation.eventlog.ModificationId
import com.normation.rudder.domain.policies.ActiveTechniqueId
import com.normation.rudder.ncf.BundleName
import com.normation.rudder.ncf.CheckConstraint
import com.normation.rudder.ncf.ResourceFile
import com.normation.rudder.ncf.ResourceFileState
import com.normation.rudder.ncf.Technique
import com.normation.rudder.ncf.TechniqueReader
import com.normation.rudder.ncf.TechniqueSerializer
import com.normation.rudder.ncf.TechniqueWriter
import com.normation.rudder.repository.RoDirectiveRepository
import com.normation.rudder.repository.json.DataExtractor.OptionnalJson
import com.normation.rudder.repository.xml.GitFindUtils
import com.normation.rudder.rest.ApiPath
import com.normation.rudder.rest.ApiVersion
import com.normation.rudder.rest.AuthzToken
import com.normation.rudder.rest.RestExtractorService
import com.normation.rudder.rest.TwoParam
import com.normation.rudder.rest.{NcfApi => API}
import com.normation.utils.StringUuidGenerator
import net.liftweb.common.Box
import net.liftweb.common.Failure
import net.liftweb.common.Full
import net.liftweb.common.Loggable
import net.liftweb.http.LiftResponse
import net.liftweb.http.Req
import net.liftweb.json.JsonAST.JArray
import net.liftweb.json.JsonAST.JField
import net.liftweb.json.JsonAST.JObject
import net.liftweb.json.JsonAST.JString
import net.liftweb.json.JsonAST.JValue
import zio._

class NcfApi(
    techniqueWriter     : TechniqueWriter
  , techniqueReader     : TechniqueReader
  , techniqueRepository : TechniqueRepository
  , restExtractorService: RestExtractorService
  , techniqueSerializer : TechniqueSerializer
  , uuidGen             : StringUuidGenerator
  , gitReposProvider    : GitRepositoryProvider
) extends LiftApiModuleProvider[API] with Loggable{

  import com.normation.rudder.rest.RestUtils._
  val dataName = "techniques"

  def resp ( function : Box[JValue], req : Req, errorMessage : String)( action : String)(implicit dataName : String) : LiftResponse = {
    response(restExtractorService, dataName,None)(function, req, errorMessage)
  }

  def actionResp ( function : Box[ActionType], req : Req, errorMessage : String, actor: EventActor)(implicit action : String) : LiftResponse = {
    actionResponse2(restExtractorService, dataName, uuidGen, None)(function, req, errorMessage)(action, actor)
  }


  def schemas = API
  def getLiftEndpoints(): List[LiftApiModule] = {
    API.endpoints.map(e => e match {
        case API.UpdateTechnique           => UpdateTechnique
        case API.CreateTechnique           => CreateTechnique
        case API.GetResources              => new GetResources[API.GetResources.type](false, API.GetResources)
        case API.GetNewResources           => new GetResources[API.GetNewResources.type ](true, API.GetNewResources)
        case API.ParameterCheck            => ParameterCheck
        case API.DeleteTechnique           => DeleteTechnique
        case API.GetTechniques             => GetTechniques
        case API.GetMethods                => GetMethods
        case API.UpdateMethods             => UpdateMethods
        case API.UpdateTechniques          => UpdateTechniques
        case API.GetAllTechniqueCategories => GetAllTechniqueCategories
    })
  }

  class GetResources[T <:  TwoParam ](newTechnique : Boolean, val schema : T) extends LiftApiModule {

    val restExtractor = restExtractorService
    implicit val dataName = "resources"
    def process(version: ApiVersion, path: ApiPath, techniqueInfo: (String,String), req: Req, params: DefaultParams, authzToken: AuthzToken): LiftResponse = {

      import com.normation.errors.Inconsistency
      import zio.syntax._
      def getAllFiles (resourceDir : File)( file : File):List[String]  = {
        if (file.exists) {
          if (file.isRegularFile) {
            resourceDir.relativize(file).toString :: Nil
          } else {
            file.children.toList.flatMap(getAllFiles(resourceDir))
          }
        } else {
          Nil
        }
      }

      import ResourceFileState._
      import net.liftweb.json.JsonDSL._

      import scala.jdk.CollectionConverters._

      def toResource( fullPath : String, resourcesPath : String, state : ResourceFileState): Option[ResourceFile] = {
        // workaround https://issues.rudder.io/issues/17977 - if the fullPath does not start by resourcePath,
        // it's a bug from jgit filtering: ignore that file
        val relativePath = fullPath.stripPrefix(s"${resourcesPath}/")
        if(relativePath == fullPath) None
        else Some(ResourceFile(relativePath, state))
      }

      def serializeResourceWithState( resource : ResourceFile) = {
          (("name" -> resource.path) ~ ("state" -> resource.state.value))
      }

      val action = if (newTechnique) { "newTechniqueResources" } else { "techniqueResources" }
      def getRessourcesStatus = {

        for {
          resourcesPath <- if (newTechnique) {
                              s"workspace/${techniqueInfo._1}/${techniqueInfo._2}/resources".succeed
                           } else {
                             for {
                               optTechnique <- techniqueReader.readTechniquesMetadataFile.map(_.find(_.bundleName.value == techniqueInfo._1))
                               category <- optTechnique.map(_.category.succeed).getOrElse(Inconsistency(s"No technique found when looking for technique '${techniqueInfo._1}' resources").fail)
                             } yield {
                               s"techniques/${category}/${techniqueInfo._1}/${techniqueInfo._2}/resources"
                             }
                           }


          status   <- GitFindUtils.getStatus(gitReposProvider.git, List(resourcesPath)).chainError(
                        s"Error when getting status of resource files of technique ${techniqueInfo._1}/${techniqueInfo._2}"
                      )
          resourceDir = File(gitReposProvider.db.getDirectory.getAbsolutePath, resourcesPath)
          allFiles    <- IOResult.effect(s"Error when getting all resource files of technique ${techniqueInfo._1}/${techniqueInfo._2} ") {
                        getAllFiles(resourceDir)(resourceDir)
                      }
        } yield {

          // New files not added
          val added = status.getUntracked.asScala.toList.flatMap(toResource(_, resourcesPath, New))
          // Files modified and not added
          val modified = status.getModified.asScala.toList.flatMap(toResource(_, resourcesPath, Modified))
          // Files deleted but not removed from git
          val removed = status.getMissing.asScala.toList.flatMap(toResource(_, resourcesPath, Deleted))

          val filesNotCommitted = modified ::: added ::: removed

          // We want to get all files from the resource directory and remove all added/modified/deleted files so we can have the list of all files not modified
          val untouched = allFiles.filterNot(f => filesNotCommitted.exists(_.path == f)).flatMap(toResource(_, resourcesPath,  Untouched))

          // Create a new list with all a
          JArray((filesNotCommitted ::: untouched).map(serializeResourceWithState))
        }
      }

      resp(getRessourcesStatus.toBox, req, "Could not get resource state of technique")(action)
    }
  }


  object DeleteTechnique extends LiftApiModule {
    val schema = API.DeleteTechnique
    val restExtractor = restExtractorService
    implicit val dataName = "techniques"

    def process(version: ApiVersion, path: ApiPath, techniqueInfo: (String, String), req: Req, params: DefaultParams, authzToken: AuthzToken): LiftResponse = {

      val modId = ModificationId(uuidGen.newUuid)


      val content =
        for {
          force <- restExtractorService.extractBoolean("force")(req)(identity)map(_.getOrElse(false))
          _ <- techniqueWriter.deleteTechnique(techniqueInfo._1, techniqueInfo._2, force, modId, authzToken.actor).toBox
        } yield {
          import net.liftweb.json.JsonDSL._
          ( ("id" -> techniqueInfo._1 )
          ~ ("version"  -> techniqueInfo._2 )
          )
        }

      resp(content,req,"delete technique")("deleteTechnique")

    }
  }
  object UpdateTechnique extends LiftApiModule0 {
    val schema = API.UpdateTechnique
    val restExtractor = restExtractorService
    def process0(version: ApiVersion, path: ApiPath, req: Req, params: DefaultParams, authzToken: AuthzToken): LiftResponse = {
      val modId = ModificationId(uuidGen.newUuid)
      val response =
        for {
          json      <- req.json ?~! "No JSON data sent"
          methods   <- restExtractor.extractGenericMethod(json \ "methods")
          methodMap =  methods.map(m => (m.id,m)).toMap
          technique <- restExtractor.extractNcfTechnique(json \ "technique", methodMap, false)
          updatedTechnique <- techniqueWriter.writeTechniqueAndUpdateLib(technique, methodMap, modId, authzToken.actor ).toBox
        } yield {
          JObject(JField("technique", techniqueSerializer.serializeTechniqueMetadata(updatedTechnique)))
        }
      val wrapper : ActionType = {
        case _ => response
      }
      actionResp(Full(wrapper), req, "Could not update ncf technique", authzToken.actor)("UpdateTechnique")
    }
  }

  object ParameterCheck extends LiftApiModule0 {
    val schema = API.ParameterCheck
    val restExtractor = restExtractorService
    implicit val dataName = "parameterCheck"
    def process0(version: ApiVersion, path: ApiPath, req: Req, params: DefaultParams, authzToken: AuthzToken): LiftResponse = {

      import com.normation.rudder.ncf.Constraint._
      import net.liftweb.json.JsonDSL._
      val response =
        for {
          json                  <- req.json ?~! "No JSON data sent"
          (value,constraints)   <- restExtractor.extractParameterCheck(json)
          check                 =  CheckConstraint.check(constraints,value)
        } yield {
          check match {
            case NOK(cause) => ("result" -> false) ~ ("errors" -> cause.toList)
            case OK         => ("result" -> true) ~ ("errors" -> Nil)
          }
        }
      resp(response, req, "Could not check parameter constraint")("checkParameter")
    }
  }

  object GetTechniques extends  LiftApiModule0 {

    val schema = API.GetTechniques
    val restExtractor = restExtractorService
    implicit val dataName = "techniques"

    def process0(version: ApiVersion, path: ApiPath, req: Req, params: DefaultParams, authzToken: AuthzToken): LiftResponse = {
      val response = for {
        techniques <- techniqueReader.readTechniquesMetadataFile.toBox
      } yield {
         JArray(techniques.map(techniqueSerializer.serializeTechniqueMetadata))
      }
      resp(response, req, "Could not get techniques metadata")("getTechniques")

    }

  }


  object GetMethods extends  LiftApiModule0 {

    val schema = API.GetMethods
    val restExtractor = restExtractorService
    implicit val dataName = "methods"

    def process0(version: ApiVersion, path: ApiPath, req: Req, params: DefaultParams, authzToken: AuthzToken): LiftResponse = {
      val response = for {
        methods <- techniqueReader.readMethodsMetadataFile
      } yield {
        JObject(methods.toList.map(m => JField(m._1.value, techniqueSerializer.serializeMethodMetadata(m._2))))
      }
      resp(response.toBox, req, "Could not get generic methods metadata")("getMethods")
    }

  }

  object UpdateMethods extends  LiftApiModule0 {

    val schema = API.UpdateMethods
    val restExtractor = restExtractorService
    implicit val dataName = "methods"

    def process0(version: ApiVersion, path: ApiPath, req: Req, params: DefaultParams, authzToken: AuthzToken): LiftResponse = {
      val response= for {
        _ <- techniqueReader.updateMethodsMetadataFile
        methods <- techniqueReader.readMethodsMetadataFile
      } yield {
        JObject(methods.toList.map(m => JField(m._1.value, techniqueSerializer.serializeMethodMetadata(m._2))))
      }
      resp(response.toBox, req, "Could not get generic methods metadata")("getMethods")
    }

  }


  object UpdateTechniques extends  LiftApiModule0 {

    val schema = API.UpdateTechniques
    val restExtractor = restExtractorService
    implicit val dataName = "techniques"

    def process0(version: ApiVersion, path: ApiPath, req: Req, params: DefaultParams, authzToken: AuthzToken): LiftResponse = {
      val modId = ModificationId(uuidGen.newUuid)
      val response= for {
        _          <- techniqueReader.updateTechniquesMetadataFile
        methods    <- techniqueReader.readMethodsMetadataFile
        techniques <- techniqueReader.readTechniquesMetadataFile
        _          <- ZIO.foreach(techniques)(t => techniqueWriter.writeTechnique(t, methods, modId, authzToken.actor))
      } yield {
        JArray(techniques.map(techniqueSerializer.serializeTechniqueMetadata))
     }
      resp(response.toBox, req, "Could not get generic methods metadata")("getMethods")

    }

  }


  object GetAllTechniqueCategories extends  LiftApiModule0 {

    val schema = API.GetAllTechniqueCategories
    val restExtractor = restExtractorService
    implicit val dataName = "techniqueCategories"

    def process0(version: ApiVersion, path: ApiPath, req: Req, params: DefaultParams, authzToken: AuthzToken): LiftResponse = {
      val response=
        JObject(techniqueRepository.getAllCategories.toList.filter(_._1.name.value != "/").map(c => JField(c._1.getPathFromRoot.tail.map(_.value).mkString("/"),JString(c._2.name))))

      resp(Full(response), req, "Could not get generic methods metadata")("getMethods")

    }

  }

  object CreateTechnique extends LiftApiModule0 {

    def moveRessources(technique : Technique, internalId : String) = {
      val workspacePath =      s"workspace/${internalId}/${technique.version.value}/resources"
      val finalPath =  s"techniques/${technique.category}/${technique.bundleName.value}/${technique.version.value}/resources"

      val workspaceDir = File(s"/var/rudder/configuration-repository/${workspacePath}")
      val finalDir = File(s"/var/rudder/configuration-repository/${finalPath}")

      IOResult.effect("Error when moving resource file from workspace to final destination") (
        if (workspaceDir.exists) {
          finalDir.createDirectoryIfNotExists(true)
          workspaceDir.moveTo(finalDir)(File.CopyOptions.apply(true))
          workspaceDir.parent.parent.delete()
          "ok"
        } else {
          "ok"
        } )
    }

    val schema = API.CreateTechnique
    val restExtractor = restExtractorService
    def process0(version: ApiVersion, path: ApiPath, req: Req, params: DefaultParams, authzToken: AuthzToken): LiftResponse = {
      val modId = ModificationId(uuidGen.newUuid)
      val response =
        for {
          json      <- req.json ?~! "No JSON data sent"
          methods   <- restExtractor.extractGenericMethod(json \ "methods")
          methodMap = methods.map(m => (m.id,m)).toMap
          technique <- restExtractor.extractNcfTechnique(json \ "technique", methodMap, true)
          internalId <- OptionnalJson.extractJsonString(json \ "technique", "internalId")
          _          <- if(techniqueRepository.getAll().keySet.map(_.name.value).contains(technique.bundleName.value)) {
                          Failure(s"Technique name and ID must be unique. '${technique.name}' already used")
                        } else Full(())
          // If no internalId (used to manage temporary folder for resources), ignore resources, this can happen when importing techniques through the api
          resoucesMoved <- internalId.map( internalId => moveRessources(technique,internalId).toBox).getOrElse(Full("Ok"))
          updatedTech   <- techniqueWriter.writeTechniqueAndUpdateLib(technique, methodMap, modId, authzToken.actor).toBox
        } yield {
          JObject(JField("technique", techniqueSerializer.serializeTechniqueMetadata(updatedTech)))
        }

      val wrapper : ActionType = {
        case _ => response
      }
      actionResp(Full(wrapper), req, "Could not create ncf technique", authzToken.actor)("CreateTechnique")
    }
  }
}
