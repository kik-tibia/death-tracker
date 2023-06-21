package com.kiktibia.deathtracker.tibiadata

import java.io.File
import scala.io.Source

object FileUtils {

  def getLines(file: File): List[String] = {
    val source = Source.fromFile(file)
    val lines = source.getLines().toList
    source.close()
    lines
  }

}
