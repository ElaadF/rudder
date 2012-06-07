/*
*************************************************************************************
* Copyright 2011 Normation SAS
*************************************************************************************
*
* This program is free software: you can redistribute it and/or modify
* it under the terms of the GNU Affero General Public License as
* published by the Free Software Foundation, either version 3 of the
* License, or (at your option) any later version.
*
* In accordance with the terms of section 7 (7. Additional Terms.) of
* the GNU Affero GPL v3, the copyright holders add the following
* Additional permissions:
* Notwithstanding to the terms of section 5 (5. Conveying Modified Source
* Versions) and 6 (6. Conveying Non-Source Forms.) of the GNU Affero GPL v3
* licence, when you create a Related Module, this Related Module is
* not considered as a part of the work and may be distributed under the
* license agreement of your choice.
* A "Related Module" means a set of sources files including their
* documentation that, without modification of the Source Code, enables
* supplementary functions or services in addition to those offered by
* the Software.
*
* This program is distributed in the hope that it will be useful,
* but WITHOUT ANY WARRANTY; without even the implied warranty of
* MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
* GNU Affero General Public License for more details.
*
* You should have received a copy of the GNU Affero General Public License
* along with this program. If not, see <http://www.gnu.org/licenses/agpl.html>.
*
*************************************************************************************
*/

package com.normation.rudder.services.nodes

import com.normation.inventory.domain.NodeId
import com.normation.rudder.domain.nodes.PolicyServerNodeInfo
import com.normation.rudder.domain.nodes.NodeInfo
import com.normation.inventory.domain._
import org.joda.time.DateTime
import com.normation.utils.Control._
import net.liftweb.common._

class DummyNodeInfoService extends NodeInfoService {

  val allNodes = Seq[NodeInfo](
    new PolicyServerNodeInfo(NodeId("root"), "root", "root", "root", /*"1.2.3.4"::"2.3.4.5"::Nil,*/ DateTime.now(),  Seq[Agent](Agent(name=COMMUNITY_AGENT,policyServerUUID=Some(NodeId("root")),cfengineKey=Some(PublicKey("publicKey")))), "root", DateTime.now(), false, false  ), // root server
      new NodeInfo(NodeId("one"), "one", "one", "one", "linux", /*"1.1.1.1" :: Nil,*/ DateTime.now(),  Seq[Agent](Agent(name=COMMUNITY_AGENT,policyServerUUID=Some(NodeId("root")),cfengineKey=Some(PublicKey("key")))), "root", DateTime.now(), false, false), // first child
      new NodeInfo(NodeId("two"), "two", "two", "two", "linux", /*"1.1.1.2" :: Nil,*/ DateTime.now(),Seq[Agent](Agent(name=COMMUNITY_AGENT,policyServerUUID=Some(NodeId("root")),cfengineKey=Some(PublicKey("key")))), "root", DateTime.now(), false, false), // second child
      new PolicyServerNodeInfo(NodeId("relay"), "relay", "relay", "relay", /*"1.2.3.5"::"2.3.4.15"::Nil,*/ DateTime.now(), Seq[Agent](Agent(name=COMMUNITY_AGENT,policyServerUUID=Some(NodeId("root")),cfengineKey=Some(PublicKey("publicKeyrelay")))), "root", DateTime.now(), false, false  ), // relay server
        new NodeInfo(NodeId("subone"), "subone", "subone", "subone", "linux", /*"1.1.1.1" :: Nil,*/ DateTime.now(),Seq[Agent](Agent(name=COMMUNITY_AGENT,policyServerUUID=Some(NodeId("relay")),cfengineKey=Some(PublicKey("keysubone")))) , "root", DateTime.now(), false, false) // grandchild
  )


  def getNodeInfo(nodeId: NodeId) : Box[NodeInfo] = {
    allNodes.filter(x => x.id == nodeId) match {
      case s: Seq[_] if s.size==1 => Full(s.head)
      case s: Seq[_] if s.size==0 => Empty
      case _ => Failure("Not found")
    }

  }

  def find(nodeIds: Seq[NodeId]) : Box[Seq[NodeInfo]] = {
    sequence(nodeIds) { nodeId =>
      getNodeInfo(nodeId)
    }
  }

  def getPolicyServerNodeInfo(policyServerNodeId : NodeId) : Box[PolicyServerNodeInfo] = {
    allNodes.filter(x => x.id == policyServerNodeId).filter(x => x.isInstanceOf[PolicyServerNodeInfo]) match {
      case s: Seq[_] if s.size==1 => s.headOption match {
        case Some(h:PolicyServerNodeInfo) => Full(h)
        case _ => Failure("Unconsistant type for policy server node info")
      }
      case s: Seq[_] if s.size==0 => Empty
      case _ => Failure("Not found")
    }
  }

  def findPolicyServerNodeInfos(nodeIds: Seq[NodeId]) : Box[Seq[PolicyServerNodeInfo]] = {
    sequence(nodeIds) { nodeId =>
      getPolicyServerNodeInfo(nodeId)
    }
  }

  def getAllIds() : Box[Seq[NodeId]] = {
    Full(allNodes.map(_.id))
  }

  def getAllUserNodeIds() : Box[Seq[NodeId]] = {
    Full(allNodes.filter(!_.isInstanceOf[PolicyServerNodeInfo]).map(_.id))
  }

  def getAllSystemNodeIds() : Box[Seq[NodeId]] = {
    Full(allNodes.filter(_.isInstanceOf[PolicyServerNodeInfo]).map(_.id))
  }
}