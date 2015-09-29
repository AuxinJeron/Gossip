/**
 * Created by leon on 9/29/15.
 */

import akka.actor.Actor
import akka.actor.Props
import akka.actor.ActorSystem
import Array._
import util.control.Breaks

object gossip {
  def main (args: Array[String]) {
    if (args.length != 3) {
      println("Invalid arguments. Please input arguments as the format <nodesNum> <topology> <algorithm>")
      return
    }
    var nodesNum = args(0).toInt
    var topology = args(1)
    var algorithm = args(2)
    println("Number of nodes: " + nodesNum)
    println("Topology: " + topology)
    println("Algorithm: " + algorithm)
  }
}

class BigBoss() extends Actor {

}