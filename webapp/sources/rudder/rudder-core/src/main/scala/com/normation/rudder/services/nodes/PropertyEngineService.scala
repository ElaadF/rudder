package com.normation.rudder.services.nodes

import com.normation.errors._
import com.typesafe.config.ConfigValue

trait RudderPropertyEngine {
  def name: String

  def process(namespace: List[String], parameters: ConfigValue): IOResult[String]
}


class PropertyEngineService(listOfEngine: List[RudderPropertyEngine]) {
  val engines: Map[String, RudderPropertyEngine] = listOfEngine.map(e => e.name.toLowerCase -> e).toMap

  def process(engine: String, namespace: List[String], param: ConfigValue): IOResult[String] = {
    for {
      e <- engines.get(engine.toLowerCase)
             .notOptional(s"Engine '${engine}' not found. Parameter can not be expanded: ${param}")
      interpolatedValueRes <- e.process(namespace, param)
    } yield {
      interpolatedValueRes
    }
  }
}
