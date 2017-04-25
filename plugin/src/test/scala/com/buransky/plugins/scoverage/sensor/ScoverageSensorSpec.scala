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

import java.io.File
import java.util

//import com.buransky.plugins.scoverage.language.Scala
import com.buransky.plugins.scoverage.{FileStatementCoverage, DirectoryStatementCoverage, ProjectStatementCoverage, ScoverageReportParser}
import org.junit.runner.RunWith
import org.mockito.Mockito._
import org.scalatest.junit.JUnitRunner
import org.scalatest.mockito.MockitoSugar
import org.scalatest.{FlatSpec, Matchers}
import org.sonar.api.batch.fs.{FilePredicate, FilePredicates, FileSystem}
import org.sonar.api.config.Settings
import org.sonar.api.scan.filesystem.PathResolver

import scala.collection.JavaConversions._
import com.buransky.plugins.scoverage.pathcleaner.PathSanitizer
import org.mockito.Matchers.any
import org.sonar.api.batch.fs.InputModule
import org.sonar.api.batch.sensor.Sensor
import org.sonar.api.batch.sensor.SensorContext
import org.sonar.api.batch.sensor.measure.internal.DefaultMeasure
import org.sonar.api.batch.sensor.coverage.internal.DefaultCoverage
import org.sonar.api.batch.fs.internal.DefaultInputModule
import org.mockito.stubbing.Answer
import org.mockito.invocation.InvocationOnMock
import org.sonar.api.batch.fs.InputFile
import org.sonar.api.batch.fs.InputDir
import com.buransky.plugins.scoverage.measure.ScalaMetrics
import com.buransky.plugins.scoverage.StatementPosition
import com.buransky.plugins.scoverage.CoveredStatement


@RunWith(classOf[JUnitRunner])
class ScoverageSensorSpec extends FlatSpec with Matchers with MockitoSugar {
  
  behavior of "execute on one module"

  it should "set 0% coverage for a module without children" in new AnalyseScoverageSensorScope("test-project") {
    // Setup
    
    val pathToScoverageReport = "#path-to-scoverage-report#"
    val reportAbsolutePath = "#report-absolute-path#"
    
    // project coverage
    
    val statement1 = CoveredStatement(StatementPosition(line=2, 4), StatementPosition(line=2, 8), 1)
    val statement2 = CoveredStatement(StatementPosition(line=2, 10), StatementPosition(line=2, 12), 1)
    
    val projectStatementCoverage =
      ProjectStatementCoverage(moduleKey, List(
        DirectoryStatementCoverage("x", List(
          FileStatementCoverage("a.scala", 3, 2, List(statement1, statement2)),
          FileStatementCoverage("b.scala", 1, 0, Nil)
        ))
      ))
    
    // settings
    when(settings.getString(SCOVERAGE_REPORT_PATH_PROPERTY)).thenReturn(pathToScoverageReport)
       
    // fake a tiny file tree
    val moduleBaseDir = mock[java.io.File]
    val aScala = mock[InputFile]
    val bScala = mock[InputFile]
    val xScala = mock[InputDir]

    when(aScala.file()).thenReturn(mock[File])
    when(aScala.key()).thenReturn("aScala")
    when(aScala.lines()).thenReturn(20)
    
    when(bScala.file()).thenReturn(mock[File])
    when(bScala.key()).thenReturn("bScala")
    when(bScala.lines()).thenReturn(20)
    
    when(xScala.key()).thenReturn("xScala")
    
    when(fileSystem.baseDir).thenReturn(moduleBaseDir)
    when(fileSystem.inputFile(any[FilePredicate]())).thenReturn(aScala).thenReturn(bScala)
    when(fileSystem.inputDir(any[File])).thenReturn(xScala)
    
    val filePredicates = mock[FilePredicates]
    when(fileSystem.predicates).thenReturn(filePredicates)   
    
    // fake existence of a report file
    val reportFile = mock[java.io.File]
    when(reportFile.exists).thenReturn(true)
    when(reportFile.isFile).thenReturn(true)
    when(reportFile.getAbsolutePath).thenReturn(reportAbsolutePath)
    when(pathResolver.relativeFile(moduleBaseDir, pathToScoverageReport)).thenReturn(reportFile)

    // inject fake coverage report
    when(scoverageReportParser.parse(any[String](), any[PathSanitizer]())).thenReturn(projectStatementCoverage)

    // Execute
    execute(context)
        
    // Validate
    testStoreage.measures(moduleKey + "_" + ScalaMetrics.statementCoverage.key).value() shouldEqual 50.0d
    testStoreage.measures("xScala" + "_" + ScalaMetrics.statementCoverage.key).value() shouldEqual 50.0d
    testStoreage.measures("aScala" + "_" + ScalaMetrics.statementCoverage.key).value() shouldEqual 2.0d / 3.0d * 100.0d
    testStoreage.measures("bScala" + "_" + ScalaMetrics.statementCoverage.key).value() shouldEqual 0.0d
    
    testStoreage.measures(moduleKey + "_" + ScalaMetrics.coveredStatements.key).value() shouldEqual 2
    testStoreage.measures("xScala" + "_" + ScalaMetrics.coveredStatements.key).value() shouldEqual 2
    testStoreage.measures("aScala" + "_" + ScalaMetrics.coveredStatements.key).value() shouldEqual 2
    testStoreage.measures("bScala" + "_" + ScalaMetrics.coveredStatements.key).value() shouldEqual 0
    
    testStoreage.measures(moduleKey + "_" + ScalaMetrics.totalStatements.key).value() shouldEqual 4
    testStoreage.measures("xScala" + "_" + ScalaMetrics.totalStatements.key).value() shouldEqual 4
    testStoreage.measures("aScala" + "_" + ScalaMetrics.totalStatements.key).value() shouldEqual 3
    testStoreage.measures("bScala" + "_" + ScalaMetrics.totalStatements.key).value() shouldEqual 1

    // different than the statement coverage result
    testStoreage.coverage("aScala").coveredLines() shouldEqual 1
    testStoreage.coverage("bScala").coveredLines() shouldEqual 0
  }

  abstract class AnalyseScoverageSensorScope(val moduleKey: String) extends ScoverageSensorScope {
    
    val testStoreage = new TestStorage()
    val context = mock[SensorContext]
    when(context.fileSystem()).thenReturn(fileSystem)
    when(context.settings()).thenReturn(settings)
    when(context.module()).thenReturn(new DefaultInputModule(moduleKey))
      
    // a new measure for each call
    when(context.newMeasure()).thenAnswer(new Answer[DefaultMeasure[_]]() {
      def answer(invocation: InvocationOnMock) = new DefaultMeasure(testStoreage) 
    })
    
    // a new coverage for each call
    when(context.newCoverage()).thenAnswer(new Answer[DefaultCoverage]() {
      def answer(invocation: InvocationOnMock) = new DefaultCoverage(testStoreage) 
    })
        
    private val sanitizerMock = mock[PathSanitizer]
    override protected def createPathSanitizer(baseDir: File, sonarSources: String) = sanitizerMock
    
    private val scoverageParserMock = mock[ScoverageReportParser]  
    override protected def scoverageReportParser = scoverageParserMock
  }

  class ScoverageSensorScope extends {
  //  val scala = new Scala    
    val settings = mock[Settings]
    val pathResolver = mock[PathResolver]
    val fileSystem = mock[FileSystem]
  } with ScoverageSensor(pathResolver)

}
