/*
*************************************************************************************
* Copyright 2011 Normation SAS
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

package com.normation.rudder.web.services


import com.normation.rudder.services.reports.ReportingService
import com.normation.rudder.domain.nodes.NodeInfo
import com.normation.rudder.domain.policies.RuleId
import com.normation.rudder.domain.policies.RuleVal
import com.normation.rudder.services.servers.NodeSummaryService
import com.normation.rudder.web.model._
import com.normation.inventory.domain.NodeId
import com.normation.rudder.domain.reports.bean.Reports
import com.normation.rudder.web.components.DateFormaterService
import scala.xml._
import net.liftweb.common._
import net.liftweb.http._
import net.liftweb.util._
import Helpers._
import net.liftweb.http.js._
import JsCmds._
import JE._
import net.liftweb.http.SHtml._
import bootstrap.liftweb.LiftSpringApplicationContext.inject
import scala.collection._
import com.normation.rudder.domain.policies.DirectiveId
import com.normation.exceptions.TechnicalException
import net.liftweb.http.Templates
import com.normation.rudder.repository.ReportsRepository
import com.normation.rudder.repository.RoDirectiveRepository
import com.normation.rudder.repository.RoRuleRepository

/**
 * Show the reports from cfengine (raw data)
 */
class LogDisplayer(
    reportRepository   : ReportsRepository
  , directiveRepository: RoDirectiveRepository
  , ruleRepository     : RoRuleRepository
) {


  private val templatePath = List("templates-hidden", "node_logs_tabs")
  private def template() =  Templates(templatePath) match {
    case Empty | Failure(_,_,_) =>
      throw new TechnicalException("Template for server details not found. I was looking for %s.html".format(templatePath.mkString("/")))
    case Full(n) => n
  }

  private def content = chooseTemplate("logs","content",template)

  private def jsVarNameForId(tableId:String) = "oTable" + tableId

  private val gridName = "logsGrid"


  def asyncDisplay(nodeId : NodeId, withinPopup : Boolean) : NodeSeq = {

    val id = JsNodeId(nodeId)
    val callback =  SHtml.ajaxInvoke( () => SetHtml("logsDetails",display(nodeId))& initJs(withinPopup) )
    Script(OnLoad(JsRaw(
      s"""
        $$("#details_${id}").bind( "show", function(event, ui) {
          if(ui.panel.id== 'node_logs') { ${callback.toJsCmd} }
        });
       """
     )))
  }

  def display(nodeId : NodeId) : NodeSeq = {
    val PIMap = mutable.Map[DirectiveId, String]()
    val CRMap = mutable.Map[RuleId, String]()

    def getPIName(directiveId : DirectiveId) : String = {
      PIMap.get(directiveId).getOrElse({val result = directiveRepository.getDirective(directiveId).map(_.name).openOr(directiveId.value); PIMap += ( directiveId -> result); result } )
    }

    def getCRName(ruleId : RuleId) : String = {
      CRMap.get(ruleId).getOrElse({val result = ruleRepository.get(ruleId).map(x => x.name).openOr(ruleId.value); CRMap += ( ruleId -> result); result } )
    }

    val lines : NodeSeq = reportRepository.findReportsByNode(nodeId, None, None, None, None).flatMap {
          case Reports(executionDate, ruleId, directiveId, nodeId, serial, component, keyValue, executionTimestamp, severity, message) =>
           <tr>
            <td>{executionDate.toString("yyyy-MM-dd HH:mm:ss")}</td>
            <td>{severity}</td>
            <td>{getPIName(directiveId)}</td>
            <td>{getCRName(ruleId)}</td>
            <td>{component}</td>
            <td>{if("None" == keyValue) "-" else keyValue}</td>
            <td>{message}</td>
           </tr>
        }

    ("tbody *" #> lines).apply(content)

  }

  /**
   * If it is displayed within a popup, we only display 5 logs per page
   */
  def initJs(withinPopup: Boolean = false) : JsCmd = {
    val extension = if (withinPopup) """"iDisplayLength": 5,""" else ""

    JsRaw("var %s;".format(jsVarNameForId(gridName))) &
    OnLoad(
        JsRaw(s"""
          /* Event handler function */
          ${jsVarNameForId(gridName)} = $$('#${gridName}').dataTable({
            "asStripeClasses": [ 'color1', 'color2' ],
            "bAutoWidth": false,
            "bFilter" :true,
            "bPaginate" :true,
            "bLengthChange": true,
            "bStateSave": true,
                    "fnStateSave": function (oSettings, oData) {
                      localStorage.setItem( 'DataTables_${gridName}', JSON.stringify(oData) );
                    },
                    "fnStateLoad": function (oSettings) {
                      return JSON.parse( localStorage.getItem('DataTables_${gridName}') );
                    },
            ${extension}
            "sPaginationType": "full_numbers",
            "oLanguage": {
              "sSearch": ""
            },
            "bJQueryUI": true,
            "aaSorting":[],
            "aoColumns": [
              { "sWidth": "120px" },
              { "sWidth": "80px" },
              { "sWidth": "110px" },
              { "sWidth": "120px" },
              { "sWidth": "100px" },
              { "sWidth": "100px" },
              { "sWidth": "220px" }
            ],
            "sDom": '<"dataTables_wrapper_top"fl>rt<"dataTables_wrapper_bottom"ip>'
          });
            $$('.dataTables_filter input').attr("placeholder", "Search");
            """
        )
    )
  }

}