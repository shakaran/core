<%@page import="java.util.UUID"%>
<%@page import="com.dotmarketing.common.model.ContentletSearch"%>
<%@page import="com.dotmarketing.util.PaginatedArrayList"%>
<%@page import="com.dotmarketing.util.URLEncoder"%>
<%@page import="java.util.Date"%>
<%@page import="com.dotmarketing.portlets.contentlet.business.ContentletAPI"%>
<%@page import="java.util.ArrayList"%>
<%@page import="com.liferay.portal.model.User"%>
<%@page import="com.dotmarketing.business.web.WebAPILocator"%>
<%@page import="com.dotmarketing.portlets.contentlet.model.Contentlet"%>
<%@page import="com.dotcms.publisher.business.DotPublisherException"%>
<%@page import="java.util.Map"%>
<%@page import="com.dotcms.publisher.business.PublisherAPI"%>
<%@page import="java.util.List"%>
<%@page import="com.dotmarketing.business.APILocator"%>
<%@page import="java.util.Calendar"%>
<%@page import="com.dotmarketing.util.UtilMethods"%>
<%@ page import="com.liferay.portal.language.LanguageUtil"%>
<script type="text/javascript">
   dojo.require("dijit.form.Button");
   dojo.require("dijit.Menu");
   dojo.require("dijit.MenuItem");
</script>  
<%
  	User user = WebAPILocator.getUserWebAPI().getLoggedInUser(request);
    ContentletAPI conAPI = APILocator.getContentletAPI();
    if(user == null){
    	response.setStatus(403);
    	return;
    }
    String nastyError = "";
    long processedCounter=0;
    long errorCounter=0;

    PublisherAPI publisherAPI = PublisherAPI.getInstance();

    String sortBy = request.getParameter("sort");
    if(!UtilMethods.isSet(sortBy)){
    	sortBy="";
    }
    String offset = request.getParameter("offset");
    if(!UtilMethods.isSet(offset)){
    	offset="0";
    }
    String limit = request.getParameter("limit");
    if(!UtilMethods.isSet(limit)){
    	limit="50"; //TODO Load from properties
    }
    String query = request.getParameter("query");
    if(!UtilMethods.isSet(query)){
    	query="";
    	nastyError=LanguageUtil.get(pageContext, "publisher_Query_required");
    }

    boolean userIsAdmin = false;
    if(APILocator.getRoleAPI().doesUserHaveRole(user, APILocator.getRoleAPI().loadCMSAdminRole())){
    	userIsAdmin=true;
    }

    String layout = request.getParameter("layout");
    if(!UtilMethods.isSet(layout)) {
    	layout = "";
    }

    String referer = new URLEncoder().encode("/c/portal/layout?p_l_id=" + layout + "&p_p_id=EXT_CONTENT_PUBLISHING_TOOL&");
    List<Contentlet> iresults =  null;
    PaginatedArrayList<ContentletSearch> results =  null;
    String counter =  "0";

    boolean addQueueElements=false;
    String addQueueElementsStr = request.getParameter("add");
    if(UtilMethods.isSet(addQueueElementsStr)){
    	addQueueElements=true;	
    }
    String addOperationType = request.getParameter("action");
    if(!UtilMethods.isSet(addOperationType)){
    	addOperationType="";
    }
    try{
    	if(UtilMethods.isSet(query)){
    		//contador de procesados y fallidos
    		//Add 'only not archived' condition
    		query+=" +deleted:false";
    		
	    	if(addQueueElements){
	    		String bundeId = UUID.randomUUID().toString();
		    	if(addQueueElementsStr.equals("all")){
		    		try{
		    			//Live
		    			iresults = conAPI.search(query+" +live:true",0,0,sortBy,user,false);
		    			publisherAPI.addContentsToPublishQueue(iresults,bundeId, true);
		    			//Working
		    			iresults = conAPI.search(query+" +working:true",0,0,sortBy,user,false);
		    			publisherAPI.addContentsToPublishQueue(iresults,bundeId, false);
		    			processedCounter = iresults.size();
		    		}catch(Exception b){
    					nastyError += "<br/>Unable to add selected contents";
    					errorCounter++;
    					processedCounter = 0;	
    				}
		    	}else{
		    		List<Contentlet> contentsLive = new ArrayList<Contentlet>();
		    		List<Contentlet> contentsWorking = new ArrayList<Contentlet>();
		    		for(String item : addQueueElementsStr.split(",")){
		    			String[] value = item.split("_");
		    			if(addOperationType.equals("add")){
	    					Contentlet conLive = conAPI.findContentletByIdentifier(value[0],true,Long.parseLong(value[1]),user, false);
	    					contentsLive.add(conLive);
	    					Contentlet conWorking = conAPI.findContentletByIdentifier(value[0],false,Long.parseLong(value[1]),user, false);
	    					contentsWorking.add(conWorking);
	    					processedCounter++;							
		    			}
		    		}
		    		try{
		    			publisherAPI.addContentsToPublishQueue(contentsLive, bundeId, true);
		    			publisherAPI.addContentsToPublishQueue(contentsWorking, bundeId, false);
		    		}catch(Exception b){
    					nastyError += "<br/>Unable to add selected contents";
    					errorCounter++;
    					processedCounter = 0;	
    				}
		    	}
    		}
    		
    		
    		iresults = conAPI.search(query,new Integer(limit),new Integer(offset),sortBy,user,false);
    		results = (PaginatedArrayList) conAPI.searchIndex(query,new Integer(limit),new Integer(offset),sortBy,user,false);
    		counter = ""+results.getTotalResults();
    	}
    }catch(Exception pe){
    	iresults = new ArrayList();
    	results = new PaginatedArrayList();
    	nastyError = pe.toString();
    }
  %>
<script type="text/javascript">
 function solrAddCheckUncheckAll(){
	   var check=false;
	   if(dijit.byId("add_all").checked){
		   check=true;
	   }
	   var nodes = dojo.query('.add_to_queue');
	   dojo.forEach(nodes, function(node) {
		    dijit.getEnclosingWidget(node).set("checked",check);
	   }); 
   }
   function doLucenePagination(offset,limit) {		
		var url="layout=<%=layout%>&query=<%=UtilMethods.encodeURIComponent(query)%>&sort=<%=sortBy%>";
		url+="&offset="+offset;
		url+="&limit="+limit;		
		refreshLuceneList(url);
	}
   
   function addToPublishQueueQueue(action){
	   var url="layout=<%=layout%>&query=<%=UtilMethods.encodeURIComponent(query)%>&sort=<%=sortBy%>&offset=0&limit=<%=limit%>";	
		if(dijit.byId("add_all").checked){
			url+="&add=all";
		}else{
			var ids="";
			var nodes = dojo.query('.add_to_queue');
			   dojo.forEach(nodes, function(node) {
				   if(dijit.getEnclosingWidget(node).checked){
					   ids+=","+dijit.getEnclosingWidget(node).value; 
				   }
			   });
			if(ids != ""){   
				url+="&add="+ids.substring(1);
			}
		}
		url+="&action="+action;
		refreshLuceneList(url);	   
   }
</script>
<%if(UtilMethods.isSet(nastyError) && errorCounter == 0){%>
		<dl>
			<dt style='color:red;'><%= LanguageUtil.get(pageContext, "publisher_Query_Error") %> </dt>
			<dd><%=nastyError %></dd>
		</dl>
<%}else if(iresults.size() >0){ %>	
  <% if( processedCounter > 0 || errorCounter > 0){ %>
  	<dl>
		<dt>&nbsp;</dt><dd><span style='color:green;'><%= LanguageUtil.get(pageContext, "publisher_Processed_message") %> <%=processedCounter %></span>
		<span style='color:red;'><%= LanguageUtil.get(pageContext, "publisher_Error_Message") %> <%=errorCounter %></span></dd>
	</dl>	
	<% } %>								
	<table class="listingTable shadowBox">
		<tr>
			<th style="width:30px"><input dojoType="dijit.form.CheckBox" type="checkbox" name="add_all" value="all" id="add_all" onclick="solrAddCheckUncheckAll()" /></th>		
			<th colspan="2"><div id="addPublishQueueMenu"></div></th>			
		</tr>
		<% for(Contentlet c : iresults) {%>
			<tr>
				<td style="width:30px"><input dojoType="dijit.form.CheckBox" type="checkbox" class="add_to_queue" name="add_to_queue" value="<%=c.getIdentifier()+"_"+c.getLanguageId() %>" id="add_to_queue_<%=c.getIdentifier()%>" /></td>
				<td><a href="/c/portal/layout?p_l_id=<%=layout%>&p_p_id=EXT_11&p_p_action=1&p_p_state=maximized&p_p_mode=view&_EXT_11_struts_action=/ext/contentlet/edit_contentlet&_EXT_11_cmd=edit&inode=<%=c.getInode() %>&referer=<%=referer %>"><%=c.getTitle()%></a></td>
				<td style="width:200px"><%=UtilMethods.isSet(c.getModDate())?UtilMethods.dateToHTMLDate(c.getModDate(),"MM/dd/yyyy hh:mma"):""%></a></td>
			</tr>
		<%}%>
	</table>
	<table class="solr_listingTableNoBorder">
		<tr>
			<%
			long begin=Long.parseLong(offset);
			long end = Long.parseLong(offset)+Long.parseLong(limit);
			long total = Long.parseLong(counter);
			if(begin > 0){ 
				long previous=(begin-Long.parseLong(limit));
				if(previous < 0){
					previous=0;					
				}
			%>
			<td style="width:130px"><button dojoType="dijit.form.Button" onClick="doLucenePagination(<%=previous%>,<%=limit%>);return false;" iconClass="previousIcon"><%= LanguageUtil.get(pageContext, "publisher_Previous") %></button></td>
			<%}else{ %>
			<td style="width:130px">&nbsp;</td>
			<%} %>
			<td class="solr_tcenter" colspan="2"><strong> <%=begin+1%> - <%=end < total?end:total%> <%= LanguageUtil.get(pageContext, "publisher_Of") %> <%=total%> </strong></td>
			<%if(end < total){ 
				long next=(end < total?end:total);
			%>
			<td style="width:130px"><button class="solr_right" dojoType="dijit.form.Button" onClick="doLucenePagination(<%=next%>,<%=limit%>);return false;" iconClass="nextIcon"><%= LanguageUtil.get(pageContext, "publisher_Next") %></button></td>
			<%}else{ %>
			<td style="width:130px">&nbsp;</td>
			<%} %>
		</tr>
	</table>
	<% if(UtilMethods.isSet(nastyError)){%>
	<dl>
		<dt style='color:red;'><%= LanguageUtil.get(pageContext, "publisher_Query_Error") %> </dt>
		<dd><%=nastyError %></dd>
	</dl>
	<%} %>
	<script type="text/javascript">
	dojo.ready(function() {
	       var menu = new dijit.Menu({
	           style: "display: none;"
	       });
	       var menuItem1 = new dijit.MenuItem({
	           label: "<%= UtilMethods.escapeSingleQuotes(LanguageUtil.get(pageContext, "publisher_add_publish_queue" )) %>",
	                       iconClass: "plusIcon",
	                       onClick: function() {
	                    	   addToPublishQueueQueue('add');
	           }
	       });
	       menu.addChild(menuItem1);

	       var menuItem2 = new dijit.MenuItem({
	           label: "<%= UtilMethods.escapeSingleQuotes(LanguageUtil.get(pageContext, "publisher_remove_publish_queue" )) %>",
	                       iconClass: "deleteIcon",
	                       onClick: function() {
	                    	   addToPublishQueueQueue('remove');
	           }
	       });
	       menu.addChild(menuItem2);
	       
	       var button = new dijit.form.ComboButton({
	            label: "<%= UtilMethods.escapeSingleQuotes(LanguageUtil.get(pageContext, "publisher_add_publish_queue" )) %>",
	                        iconClass: "plusIcon",
	                        dropDown: menu,
	                        onClick: function() {
	                        	addToPublishQueueQueue('add');
	            }
	        });

	      dojo.byId("addPublishQueueMenu").appendChild(button.domNode);
	   });
	</script>
<% }else{ %>
	<table class="listingTable shadowBox">
		<tr>
			<th style="width:30px">&nbsp;</th>		
			<th colspan="2"><div id="addPublishQueueMenu"></div></th>			
		</tr>
		<tr>
			<td colspan="2" class="solr_tcenter"><%= LanguageUtil.get(pageContext, "publisher_No_Results") %></td>
		</tr>
	</table>
	<script type="text/javascript">
	dojo.ready(function() {
	       var menu = new dijit.Menu({
	           style: "display: none;"
	       });
	       var menuItem1 = new dijit.MenuItem({
	           label: "<%= UtilMethods.escapeSingleQuotes(LanguageUtil.get(pageContext, "publisher_add_publish_queue" )) %>",
	                       iconClass: "plusIcon",
	                       onClick: function() {
	                    	   //addToPublishQueueQueue('add');
	           }
	       });
	       menu.addChild(menuItem1);

	       var menuItem2 = new dijit.MenuItem({
	           label: "<%= UtilMethods.escapeSingleQuotes(LanguageUtil.get(pageContext, "publisher_remove_publish_queue" )) %>",
	                       iconClass: "deleteIcon",
	                       onClick: function() {
	                    	   //addToPublishQueueQueue('remove');
	           }
	       });
	       menu.addChild(menuItem2);
	       
	       var button = new dijit.form.ComboButton({
	            label: "<%= UtilMethods.escapeSingleQuotes(LanguageUtil.get(pageContext, "publisher_add_publish_queue" )) %>",
	                        iconClass: "plusIcon",
	                        dropDown: menu,
	                        onClick: function() {
	                        	//addToPublishQueueQueue('add');
	            },
	            disabled:true
	        });

	      dojo.byId("addPublishQueueMenu").appendChild(button.domNode);
	   });
	</script>
<%} %>