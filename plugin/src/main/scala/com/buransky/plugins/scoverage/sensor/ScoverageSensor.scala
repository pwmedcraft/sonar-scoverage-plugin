/*
 * Sonar Scoverage Plugin
 * Copyright (C) 2013 Rado Buransky
 * dev@sonar.codehaus.org
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 3 of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this program; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02
 */
package com.buransky.plugins.scoverage.sensor

import com.buransky.plugins.scoverage.measure.ScalaMetrics
import com.buransky.plugins.scoverage.pathcleaner.{BruteForceSequenceMatcher, PathSanitizer}
import com.buransky.plugins.scoverage.util.LogUtil
import com.buransky.plugins.scoverage.xml.XmlScoverageReportParser
import com.buransky.plugins.scoverage.{CoveredStatement, DirectoryStatementCoverage, FileStatementCoverage, _}
import org.sonar.api.batch.fs.{FileSystem, InputFile, InputPath}
import org.sonar.api.config.Settings
import org.sonar.api.scan.filesystem.PathResolver
import org.sonar.api.utils.log.Loggers

import scala.collection.JavaConversions._
import org.sonar.api.batch.sensor.Sensor
import org.sonar.api.batch.sensor.SensorContext
import org.sonar.api.batch.sensor.SensorDescriptor
import org.sonar.api.batch.fs.InputComponent
import org.sonar.api.batch.sensor.measure.NewMeasure
import java.io.File
import com.buransky.plugins.scoverage.pathcleaner.PathSanitizer
import org.sonar.api.batch.fs.InputDir
import com.buransky.plugins.scoverage.language.Scala

/**
 *  Main sensor for importing Scoverage report to Sonar.
 *
 * @author Rado Buransky
 */
class ScoverageSensor(pathResolver: PathResolver) extends Sensor {

  private val log = Loggers.get(classOf[ScoverageSensor])
  protected val SCOVERAGE_REPORT_PATH_PROPERTY = "sonar.scoverage.reportPath"
    
 
  /** override for testing */
  protected def scoverageReportParser: ScoverageReportParser = XmlScoverageReportParser()
 
  /** override for testing */
  protected def createPathSanitizer(baseDir: File, sonarSrcPath: String): PathSanitizer = new BruteForceSequenceMatcher(baseDir, sonarSrcPath)
  
  override def execute(context: SensorContext): Unit = {
    val reportPath = scoverageReportPath(context)
    val sonarSrcPath = scalaSourcePath(context)
    log.info(s"executing scoverage on ${context.module.key()} ( report: $reportPath)")
    
    val pathSanitizer = createPathSanitizer(context.fileSystem().baseDir(), sonarSrcPath)
    processModule(scoverageReportParser.parse(reportPath, pathSanitizer), context, sonarSrcPath)
  }

  override def describe(descriptor: SensorDescriptor): Unit = {
    descriptor
      .name("Scoverage")
      .onlyOnLanguage(Scala.key)
      .requireProperty("sonar.scoverage.reportPath")
  }
  

  private def processModule(projectCoverage: ProjectStatementCoverage, context: SensorContext, sonarSources: String) {
    // Save measures
    saveMeasures(context, context.module(), projectCoverage)

    log.info(LogUtil.f("Statement coverage for " + context.module().key() + " is " + ("%1.2f" format projectCoverage.rate)))

    // Process children
    processChildren(projectCoverage.children, context, sonarSources)
  } 

  private def processChildren(children: Iterable[StatementCoverage], context: SensorContext, directory: String) {
    children.foreach(processChild(_, context, directory))
  }

  private def processChild(dirOrFile: StatementCoverage, context: SensorContext, directory: String) {
    dirOrFile match {
      case dir: DirectoryStatementCoverage => processDirectory(dir, context, directory)
      case file: FileStatementCoverage => processFile(file, context, directory)
      case _ => throw new IllegalStateException("Not a file or directory coverage! [" +
        dirOrFile.getClass.getName + "]")
    }
  }
  
  private def processDirectory(directoryCoverage: DirectoryStatementCoverage, context: SensorContext, parentDirectory: String) {
    // save measures if any
    if (directoryCoverage.statementCount > 0) {
      val path = appendFilePath(parentDirectory, directoryCoverage.name)

      getInputDir(path, context) match {
        case Some(srcDir) => {
          // Save directory measures
          saveMeasures(context, srcDir, directoryCoverage)
        }
        case None => {
          log.warn(s"Directory not found in file system! ${path}")
        }
      }
    }
    // Process children
    processChildren(directoryCoverage.children, context, appendFilePath(parentDirectory, directoryCoverage.name))
  }  
  
  private def processFile(fileCoverage: FileStatementCoverage, context: SensorContext, directory: String) {
    val path = appendFilePath(directory, fileCoverage.name)

    getInputFile(path, context) match {
      case Some(scalaSourceFile) => {
        // Save measures
        saveMeasures(context, scalaSourceFile, fileCoverage)
        // Save line coverage. This is needed just for source code highlighting.
        saveLineCoverage(fileCoverage.statements, scalaSourceFile, context)
      }
      case None => {
        log.warn(s"File not found in file system! ${path}")
      }
    }
  }

  private def getInputFile(path: String, context: SensorContext): Option[InputFile] = {   
    val fileSystem = context.fileSystem()
    val p = fileSystem.predicates()
    
    Option(fileSystem.inputFile(p.and(
        p.hasRelativePath(path),
        p.hasLanguage("Scala"),
        p.hasType(InputFile.Type.MAIN)))
    )
  }

  private def getInputDir(path: String, context: SensorContext): Option[InputDir] = {   
    val fileSystem = context.fileSystem()
    Option(fileSystem.inputDir(pathResolver.relativeFile(fileSystem.baseDir(), path)))
  }
  
  private def saveMeasures(context: SensorContext, component: InputComponent, statementCoverage: StatementCoverage) {
    
    context.newMeasure().on(component).forMetric(ScalaMetrics.statementCoverage).withValue(statementCoverage.rate).save()
    context.newMeasure().on(component).forMetric(ScalaMetrics.totalStatements).withValue(statementCoverage.statementCount).save()
    context.newMeasure().on(component).forMetric(ScalaMetrics.coveredStatements).withValue(statementCoverage.coveredStatementsCount).save()
  
    log.debug(LogUtil.f("Save measures [" + statementCoverage.rate + ", " + statementCoverage.statementCount +
      ", " + statementCoverage.coveredStatementsCount + ", " + component.key() + "]"))
  }

  private def saveLineCoverage(coveredStatements: Iterable[CoveredStatement], file: InputFile, context: SensorContext) {
    // Convert statements to lines
    val coveredLines = StatementCoverage.statementCoverageToLineCoverage(coveredStatements)
    
    // Set line hits
    val coverage = context.newCoverage().onFile(file)
    coveredLines.foreach { coveredLine =>
      coverage.lineHits(coveredLine.line, coveredLine.hitCount)
    }

    coverage.save()
  }

    
  private def scoverageReportPath(context: SensorContext): String = {
    val path = Option(context.settings.getString(SCOVERAGE_REPORT_PATH_PROPERTY)).getOrElse {
      log.error(s"Scoverage path setting sonar.scoverage.reportPath not found for module ${context.module().key()}")
      throw new IllegalStateException(s"sonar.scoverage.reportPath must be defined")
    }
    
    pathResolver.relativeFile(context.fileSystem.baseDir, path) match {
      case report: java.io.File if report.exists && report.isFile => {
        report.getAbsolutePath
      }
      case _ => {
        log.error(s"Report not found at $path")
        throw new IllegalStateException(s"Report not found at $path")
      }
    }
  }

  private def scalaSourcePath(context: SensorContext): String = {
    val srcOption = Option(context.settings.getString("sonar.sources"))
    srcOption match {
      case Some(src) => src
      case None => {
        log.warn(s"could not find settings key sonar.sources assuming src/main/scala.")
        "src/main/scala"
      }
    }
  }
      
  private def appendFilePath(src: String, name: String) = {
    val result = src match {
      case java.io.File.separator => java.io.File.separator
      case empty if empty.isEmpty => ""
      case other => other + java.io.File.separator
    }

    result + name
  }


}
