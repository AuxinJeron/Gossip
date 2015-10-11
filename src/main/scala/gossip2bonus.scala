import akka.actor.{ActorRef, Actor, Props, ActorSystem, PoisonPill}
import scala.util.control.Breaks._
import scala.Array._

/**
 * Created by leon on 10/4/15.
 */
object gossip2bonus {
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
      val boss = system.actorOf(Props(new BigBoss2Bonus()), name = "BigBoss")
      DestoryerInstance.destoryer = system.actorOf(Props(new Destoryer), name = "Destoyer")
      algorithm match {
        case "gossip" => boss ! "gossip"
        case "push-sum" =>
          boss ! "push-sum"
          DestoryerInstance.destoryer ! "start"
      }
    }
  }
}

object DestoryerInstance {
  var destoryedNum = 0
  var destoryer: ActorRef = null
}

class Destoryer extends  Actor {
  var isOver = false
  def receive = {
    case "start" =>
      // destory some nodes through topodiagram, we can use topodiagram to get knowledge of the alive nodes
      // in real world, a node know can not send a message by testing another node first, in project,
      // we deal with this situation by check the topodiagram alive nodes. And BigBoss also can inform of
      // a failed node by not accepting its response for a long time, but we use topodiagram to make BigBoss
      // get informed more easily
      // within random time, we kill an random node
      val i = util.Random.nextInt(topoDiagram.aliveDiagram.length)
      Thread.sleep(util.Random.nextInt(1000))
      if (topoDiagram.aliveDiagram(i) == 0) {
        topoDiagram.disableNode(i)
        allNodes.localActor(i) ! PoisonPill
        DestoryerInstance.destoryedNum += 1
        println("Destory node " + i)
      }
      //if (this.isOver == false) self ! "start"
    case "stop" =>
      this.isOver = true
      context.stop(self)
  }
}

class Reminder(ms: Int, actorRef: ActorRef) extends Actor {
  var isOver = false
  def receive = {
    case "start" =>
      Thread.sleep(ms)
      actorRef ! "remind"
      if (this.isOver == false) self ! "start"
    case "stop" =>
      println("Stop destory")
      this.isOver = true
      context.stop(self)
  }
}

class BigBoss2Bonus extends Actor {
  var totalNodesNum = topoDiagram.diagram.length
  var startTime = System.currentTimeMillis()
  var lastRoundTime = System.currentTimeMillis()
  var gossipNum = 0
  var pushSumNum = 0
  var activeNodes = ofDim[Int](topoDiagram.diagram.length)
  var shouldResponsesNum = 0
  var responsesNum = 0
  var reminder: ActorRef = context.actorOf(Props(new Reminder(2000, self)), name="reminder")
  def receive = {
    case addActiveNode(nodeNo: Int) =>
      //println("Node " + nodeNo + " become active")
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
        allNodes.localActor(i) = context.actorOf(Props(new Node2Bonus(i, self)), name="node-" + i)
        allNodes.localActor(i) ! initialSum(i, 1)
      }
      this.startTime = System.currentTimeMillis()
      this.lastRoundTime = System.currentTimeMillis()
      val nodeNo = util.Random.nextInt(this.totalNodesNum)
      allNodes.localActor(nodeNo) ! "push-sum"
      this.activeNodes(nodeNo) = 1
      this.shouldResponsesNum = 1
      this.responsesNum = 0
      // self ! "push-sum check time out" // should use Thread.sleep in the target actor, which will block this actor
      // use another actor as reminder instead
      this.reminder ! "start"
    case finishGossipRound(nodeNo: Int) =>
      this.responsesNum += 1
      if (this.responsesNum == this.shouldResponsesNum) {
        //println("Finish one push-sum round response " + this.responsesNum + " should response " + this.shouldResponsesNum)
        this.responsesNum = 0
        this.shouldResponsesNum = 0
        this.lastRoundTime = System.currentTimeMillis()
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
        println("There are " + DestoryerInstance.destoryedNum + " nodes been destoryed")
        context.system.shutdown()
      }
    case "finish push-sum" =>
      //println("Should finish " + this.pushSumNum + " " + this.totalNodesNum)
      this.pushSumNum += 1
      if (this.pushSumNum == this.totalNodesNum) {
        val time = System.currentTimeMillis() - this.startTime
        println("The time of convergence is " + time + "ms")
        println("There are " + DestoryerInstance.destoryedNum + " nodes been destoryed")
        this.synchronized {
          this.reminder ! "stop"
          this.reminder ! PoisonPill
          DestoryerInstance.destoryer ! "stop"
          context.system.shutdown()
        }
      }
    case "remind" =>
      println("BigBoss get reminder")
      if (System.currentTimeMillis() - this.lastRoundTime >= 10000) {
        println("Push-sum round time out")
        this.totalNodesNum = 0
        var activeNum = 0
        for (i <- topoDiagram.aliveDiagram.indices) {
          if (topoDiagram.aliveDiagram(i) == -1) {
            this.activeNodes(i) = 0
            this.totalNodesNum += 1
          }
          else if (this.activeNodes(i) == 1) {
            activeNum += 1
          }
        }
        //println("There are " + activeNum + " active nodes and total " + this.totalNodesNum + " alive nodes")
        if (activeNum == 0) {
          this.pushSumNum = this.totalNodesNum - 1
          self ! "finish push-sum"
        }
        else {
          this.responsesNum = this.shouldResponsesNum - 1
          self ! finishGossipRound(-1)
        }
      }
  }
}

class Node2Bonus(nodeNo: Int, boss: ActorRef) extends Actor {
  var rumorsNum = 0
  var sum: Double = 0
  var weight: Double = 1
  var ratio: Double = 0
  var equalRound = 0
  var lastSendTime = System.currentTimeMillis()
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
        //this.reminder ! "start"
      }
      boss ! finishGossipRound(this.nodeNo)
    case pushSum(s: Double, w: Double) =>
      //println("Node " + this.nodeNo + " receive with ratio " + this.ratio + " origin equalround " + this.equalRound +" from node " + sender().path.name)
      println("Node " + this.nodeNo + " receive s " + s + " w " + w + " from " + sender().path.name)
      if (this.rumorsNum == 0) {
        this.rumorsNum = 1
        boss ! addActiveNode(this.nodeNo)
        //this.reminder ! "start"
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
        //this.reminder ! "stop"
      }
      boss ! finishGossipRound(this.nodeNo)
    case "push-sum send" =>
      var isNeighbour = 0
      var neighbourNo = this.nodeNo
      val searchingTime = System.currentTimeMillis()
      breakable{
        do {
          if (System.currentTimeMillis() - searchingTime >= 10000) {
            println("Node " + this.nodeNo + " send time out")
            this.boss ! "finish push-sum"
            topoDiagram.disableNode(this.nodeNo)
            break()
          }
          else {
            neighbourNo = util.Random.nextInt(topoDiagram.diagram.length)
            isNeighbour = topoDiagram.diagram(nodeNo)(neighbourNo)
            //println("nodeNo " + nodeNo + " neightNo " + neighbourNo + " isNeighbour " + isNeighbour + " isalive " + topoDiagram.aliveDiagram(neighbourNo))
          }
        } while (isNeighbour == 0 || nodeNo == neighbourNo || topoDiagram.aliveDiagram(neighbourNo) == -1)
      }
      this.synchronized{
        allNodes.localActor(neighbourNo) ! pushSum(this.sum / 2.0, this.weight / 2.0)
        this.lastSendTime = System.currentTimeMillis()
        this.sum = this.sum / 2
        this.weight = this.weight / 2
      }
  }
}
