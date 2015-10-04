/**
 * Created by leon on 9/29/15.
 */

import akka.actor.{ActorRef, Actor, Props, ActorSystem}
import com.sun.org.apache.xml.internal.security.algorithms.JCEMapper.Algorithm
import Array._
import util.control.Breaks

case class addActiveNode(nodeNo: Int)
case class initialSum(value: Double, weight: Double)
case class pushSum(sum: Double, weight: Double)
case class finishGossipRound(nodeNo: Int)

object topoDiagram {
  var diagram: Array[Array[Int]] = null
  var aliveDiagram: Array[Int] = null
  var aliveCount = 0

  def disableNode(i: Int) = {
    if (i < this.aliveDiagram.length) {
      this.aliveDiagram(i) = -1
      this.aliveCount -= 1
    }
    for (j <- this.diagram.indices) {
      if (i < this.diagram.length) {
        this.diagram(j)(i) = 0
      }
    }
  }

  def hasNeighbouAchieved(i: Int) : Boolean = {
    if (i < this.diagram.length) {
      for (j <- this.diagram.indices) {
        if (this.diagram(i)(j) == 1) return true
      }
    }
    return false
  }

  def createTopology(nodesNum: Int, topology: String) : Boolean = {
    this.diagram = ofDim[Int](nodesNum, nodesNum)
    this.aliveDiagram = ofDim[Int](nodesNum)
    this.aliveCount = nodesNum

    for (i <- this.aliveDiagram.indices) {
      this.aliveDiagram(i) = 0
    }
    topology match {
      case "full" =>
        for (i <- this.diagram.indices) {
          for (j <- this.diagram.indices) {
            if (i != j) this.diagram(i)(j) = 1
            else this.diagram(i)(j) = 0
          }
        }
        true
      case "3D" =>
        this.create3DTopology(nodesNum)
        true
      case "line" =>
        this.createLineTopology(nodesNum)
        true
      case "imp3D" =>
        this.createImp3DTopology(nodesNum)
        true
      case _ =>
        println("Did not recognize the topology: " + topology)
        false
    }
  }

  def create3DTopology(nodesNum: Int) = {
    this.diagram = ofDim[Int](nodesNum, nodesNum)
    // build a 3d model
    var col = 1
    while (col * col < nodesNum) {
      col += 1
    }
    val matrix = ofDim[Int](col, col)
    for (i <- matrix.indices) {
      for (j <- matrix.indices) {
        if (i * col + j >= 0 && i * col + j < nodesNum)
          matrix(i)(j) = i * col + j
        else
          matrix(i)(j) = -1
      }
    }
    for (i <- matrix.indices) {
      for (j <- matrix.indices) {
        if (matrix(i)(j) >=0 && matrix(i)(j) < nodesNum) {
          // left
          if (j - 1 >= 0 && j - 1 < matrix.length) {
            if (matrix(i)(j - 1) >= 0 && matrix(i)(j - 1) < nodesNum && matrix(i)(j-1) != -1)
              this.diagram(matrix(i)(j))(matrix(i)(j-1)) = 1
          }
          // top
          if (i - 1 >= 0 && i - 1 < matrix.length) {
            if (matrix(i-1)(j) >= 0 && matrix(i-1)(j) < nodesNum && matrix(i-1)(j) != -1)
              this.diagram(matrix(i)(j))(matrix(i-1)(j)) = 1
          }
          // right
          if (j + 1 >= 0 && j + 1 < matrix.length) {
            if (matrix(i)(j+1) >= 0 && matrix(i)(j+1) < nodesNum && matrix(i)(j+1) != -1)
              this.diagram(matrix(i)(j))(matrix(i)(j+1)) = 1
          }
          // bottom
          if (i + 1 >= 0 && i + 1 < matrix.length) {
            if (matrix(i+1)(j) >= 0 && matrix(i+1)(j) < nodesNum && matrix(i+1)(j) != -1)
              this.diagram(matrix(i)(j))(matrix(i+1)(j)) = 1
          }
        }
      }
    }
  }

  def createLineTopology(nodesNum: Int) = {
    this.diagram = ofDim[Int](nodesNum, nodesNum)
    for (i <- this.diagram.indices) {
      for (j <- this.diagram.indices) {
        if (i == j + 1 || i == j - 1) this.diagram(i)(j) = 1
        else this.diagram(i)(j) = 0
      }
    }
  }

  def createImp3DTopology(nodesNum: Int) = {
    this.create3DTopology(nodesNum)
    var randomNum = 0
    for (i <- this.diagram.indices) {
      do {
        randomNum = util.Random.nextInt(this.diagram.length)
      } while (this.diagram(i)(randomNum) != 1)
      this.diagram(i)(randomNum) = 1
    }
  }
}

object allNodes {
  var localActor: Array[ActorRef] = new Array[ActorRef](1)
}

object gossip {
  def main (args: Array[String]) {
    if (args.length != 3) {
      println("Invalid arguments. Please input arguments as the format <nodesNum> <topology> <algorithm>")
      return
    }
    val nodesNum = args(0).toInt
    val topology = args(1)
    val algorithm = args(2)
    println("Number of nodes: " + nodesNum)
    println("Topology: " + topology)
    println("Algorithm: " + algorithm)

    // build topology
    val success = topoDiagram.createTopology(nodesNum, topology)
    // build Big Boss
    if (success) {
      val system = ActorSystem("GossipSystem")
      val boss = system.actorOf(Props(new BigBoss()), name = "BigBoss")
      algorithm match {
        case "gossip" => boss ! "gossip"
        case "push-sum" => boss ! "push-sum"
      }
    }
  }
}

class BigBoss extends Actor {
  var totalNodesNum = topoDiagram.diagram.length
  var startTime = System.currentTimeMillis()
  var gossipNum = 0
  var pushSumNum = 0
  var activeNodes = ofDim[Int](topoDiagram.diagram.length)
  var shouldResponsesNum = 0
  var responsesNum = 0
  def receive = {
    case addActiveNode(nodeNo: Int) =>
      if (this.activeNodes(nodeNo) != 1) {
        this.activeNodes(nodeNo) = 1
      }
    case "gossip" =>
      allNodes.localActor = new Array[ActorRef](totalNodesNum)
      for (i <- 0 until totalNodesNum) {
        allNodes.localActor(i) = context.actorOf(Props(new Node(i, self)), name="node-" + i)
      }

      this.startTime = System.currentTimeMillis()
      allNodes.localActor(util.Random.nextInt(this.totalNodesNum)) ! "gossip"
    case "push-sum" =>
      allNodes.localActor = new Array[ActorRef](totalNodesNum)
      for (i <- 0 until totalNodesNum) {
        allNodes.localActor(i) = context.actorOf(Props(new Node(i, self)), name="node-" + i)
        allNodes.localActor(i) ! initialSum(i, 1)
      }
      this.startTime = System.currentTimeMillis()
      val nodeNo = util.Random.nextInt(this.totalNodesNum)
      allNodes.localActor(nodeNo) ! "push-sum"
      this.activeNodes(nodeNo) = 1
      this.shouldResponsesNum = 1
      this.responsesNum = 0
    case finishGossipRound(nodeNo: Int) =>
      this.responsesNum += 1
      if (this.responsesNum == this.shouldResponsesNum) {
        this.responsesNum = 0
        this.shouldResponsesNum = 0
        this.synchronized{
          for (i <- this.activeNodes.indices) {
            if (this.activeNodes(i) == 1) {
              this.shouldResponsesNum += 1
              allNodes.localActor(i) ! "push-sum send"
            }
          }
        }
      }
    case "finish gossip" =>
      this.gossipNum += 1
      if (this.gossipNum == this.totalNodesNum) {
        val time = System.currentTimeMillis() - this.startTime
        println("The time of convergence is " + time + "ms")
        context.system.shutdown()
      }
    case "finish push-sum" =>
      this.pushSumNum += 1
      if (this.pushSumNum == this.totalNodesNum) {
        val time = System.currentTimeMillis() - this.startTime
        println("The time of convergence is " + time + "ms")
        context.system.shutdown()
      }
  }
}

class Node(nodeNo: Int, boss: ActorRef) extends Actor {
  var rumorsNum = 0
  var sum: Double = 0
  var weight: Double = 1
  var ratio: Double = 0
  var equalRound = 0
  def receive = {
    case "gossip" =>
      // receive a new rumor
      rumorsNum += 1
      //println("Node " + this.nodeNo + " now has " + rumorsNum.toString +" rumors")
      self ! "gossip-round"
      if (rumorsNum == 10) {
        self.synchronized {
          //topoDiagram.disableNode(this.nodeNo)
          println("Node " + this.nodeNo + " now has got 10 rumors and finishes gossip.")
          this.boss ! "finish gossip"
        }
        //context.stop(self)
      }
    case "gossip-round" =>
      if (topoDiagram.aliveCount == 1 && rumorsNum < 10) {
        //topoDiagram.disableNode(this.nodeNo)
        println("Node " + this.nodeNo + " now has got " + rumorsNum + " rumors and finishes gossip.")
        this.boss ! "finish gossip"
        //context.stop(self)
      }
      else {
        // send this rumor
        var isNeighbour = 0
        var neighbourNo = this.nodeNo
        do {
          neighbourNo = util.Random.nextInt(topoDiagram.diagram.length)
          isNeighbour = topoDiagram.diagram(nodeNo)(neighbourNo)
          //println("nodeNo " + nodeNo + " neightNo " + neighbourNo + " isNeighbour " + isNeighbour + " isalive " + topoDiagram.aliveDiagram(neighbourNo))
        } while (isNeighbour == 0 || nodeNo == neighbourNo || topoDiagram.aliveDiagram(neighbourNo) == -1)
        allNodes.localActor(neighbourNo) ! "gossip"
        self ! "gossip-round"
      }
    case initialSum(v: Double, w: Double) =>
      this.sum = v
      this.weight = w
      this.ratio = this.sum / this.weight
      this.equalRound = 0
    case "push-sum" =>
      if (this.rumorsNum == 0) {
        this.rumorsNum = 1
        boss ! addActiveNode(this.nodeNo)
      }
      boss ! finishGossipRound(this.nodeNo)
    case pushSum(s: Double, w: Double) =>
      //println("Node " + this.nodeNo + " receive with ratio " + this.ratio + " origin equalround " + this.equalRound +" from node " + sender().path.name)
      println("Node " + this.nodeNo + " receive s " + s + " w " + w + " from " + sender().path.name)
      if (this.rumorsNum == 0) {
        this.rumorsNum = 1
        boss ! addActiveNode(this.nodeNo)
      }
      this.sum += s
      this.weight += w
      val newRatio: Double = this.sum / this.weight
      this.synchronized {
        if (Math.abs(newRatio - this.ratio) / this.ratio <= 1e-10) {
          this.equalRound += 1
        }
        else if (this.equalRound >= 3) {
          this.equalRound += 1
        }
        else {
          this.equalRound = 0
        }
        this.ratio = newRatio
        //self ! "push-sum send"
      }
      if (this.equalRound == 3) {
        println("Node " + this.nodeNo + " now has terminated with ratio " + this.ratio)
        this.boss ! "finish push-sum"
      }
      boss ! finishGossipRound(this.nodeNo)
    case "push-sum send" =>
      var isNeighbour = 0
      var neighbourNo = this.nodeNo
      do {
        neighbourNo = util.Random.nextInt(topoDiagram.diagram.length)
        isNeighbour = topoDiagram.diagram(nodeNo)(neighbourNo)
        //println("nodeNo " + nodeNo + " neightNo " + neighbourNo + " isNeighbour " + isNeighbour + " isalive " + topoDiagram.aliveDiagram(neighbourNo))
      } while (isNeighbour == 0 || nodeNo == neighbourNo || topoDiagram.aliveDiagram(neighbourNo) == -11)
      this.synchronized{
        allNodes.localActor(neighbourNo) ! pushSum(this.sum / 2.0, this.weight / 2.0)
        this.sum = this.sum / 2
        this.weight = this.weight / 2
      }
      //self ! "push-sum send"
  }
}