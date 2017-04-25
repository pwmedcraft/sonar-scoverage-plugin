package com.buransky.plugins.scoverage

import org.sonar.api.resources.Languages
import org.sonar.api.{Extension, ExtensionProvider, ServerExtension}

import scala.collection.JavaConversions._
import scala.collection.mutable.ListBuffer
import com.buransky.plugins.scoverage.language.Scala

class ScoverageExtensionProvider(languages: Languages) extends ExtensionProvider with ServerExtension {
 
  override def provide(): Object = {
    val result = ListBuffer[Object]()

    if (languages.get(Scala.key) == null) {
      // Fix issue with multiple Scala plugins:
      // https://github.com/RadoBuransky/sonar-scoverage-plugin/issues/31
      result += classOf[Scala]
    }

    result
  }
}
