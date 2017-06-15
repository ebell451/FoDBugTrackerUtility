package com.fortify.processrunner.ssc.processor.retrieve;

import java.util.List;

import javax.ws.rs.HttpMethod;
import javax.ws.rs.client.WebTarget;

import org.apache.commons.lang.StringUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import com.fortify.processrunner.common.context.IContextCurrentVulnerability;
import com.fortify.processrunner.context.Context;
import com.fortify.processrunner.context.ContextPropertyDefinition;
import com.fortify.processrunner.context.ContextPropertyDefinitions;
import com.fortify.processrunner.processor.AbstractProcessor;
import com.fortify.processrunner.processor.CompositeProcessor;
import com.fortify.processrunner.processor.IProcessor;
import com.fortify.processrunner.ssc.connection.SSCConnectionFactory;
import com.fortify.processrunner.ssc.context.IContextSSCSource;
import com.fortify.ssc.connection.IssueSearchOptions;
import com.fortify.ssc.connection.SSCAuthenticatingRestConnection;
import com.fortify.util.json.JSONMap;
import com.fortify.util.spring.SpringExpressionUtil;
import com.fortify.util.spring.expression.SimpleExpression;

/**
 * <p>This {@link IProcessor} implementation retrieves a list of
 * vulnerabilities from SSC for the application version id specified 
 * as a {@link Context} property.</p>
 * 
 * <p>For each individual vulnerability, the {@link IProcessor} 
 * implementation configured via 
 * {@link #setVulnerabilityProcessor(IProcessor)} will be called
 * to process the current vulnerability. The current vulnerability
 * can be accessed by the vulnerability processor using the
 * 'CurrentVulnerability' {@link Context} property.</p>
 * 
 * <p>If the 'SSCTopLevelFilterParamValue' {@link Context} property 
 * has been set (usually by adding filters via 
 * {@link SSCFilterOnTopLevelFields}), this filter parameter value 
 * will be passed on to SSC to allow SSC to filter the vulnerabilities 
 * being returned.</p>
 * 
 * @author Ruud Senden
 */
public class SSCProcessorRetrieveVulnerabilities extends AbstractProcessor {
	private static final Log LOG = LogFactory.getLog(SSCProcessorRetrieveVulnerabilities.class);
	private static final SimpleExpression EXPR_COUNT = SpringExpressionUtil.parseSimpleExpression("count");
	private SimpleExpression rootExpression = SpringExpressionUtil.parseSimpleExpression("data");
	private final IssueSearchOptions issueSearchOptions = new IssueSearchOptions();
	private IProcessor vulnerabilityProcessor;
	
	@Override
	public void addContextPropertyDefinitions(ContextPropertyDefinitions contextPropertyDefinitions, Context context) {
		contextPropertyDefinitions.add(new ContextPropertyDefinition("SSCApplicationVersionId","SSC application version id from which to retrieve vulnerabilities",true));
		if ( vulnerabilityProcessor != null ) {
			vulnerabilityProcessor.addContextPropertyDefinitions(contextPropertyDefinitions, context);
		}
	}
	
	public SSCProcessorRetrieveVulnerabilities() {
		vulnerabilityProcessor = new CompositeProcessor();
	}
	
	public SSCProcessorRetrieveVulnerabilities(IProcessor... processors) {
		vulnerabilityProcessor = new CompositeProcessor(processors);
	}
	
	@Override
	public boolean process(Context context) {
		IProcessor processor = getVulnerabilityProcessor();
		processor.process(Phase.PRE_PROCESS, context);
		IContextSSCSource contextSSC = context.as(IContextSSCSource.class);
		IContextCurrentVulnerability contextCurrentVulnerability = context.as(IContextCurrentVulnerability.class);
		SSCAuthenticatingRestConnection conn = SSCConnectionFactory.getConnection(context);
		String filterParamValue = contextSSC.getSSCTopLevelFilterParamValue();
		LOG.info("[SSC] Retrieving vulnerabilities for application version id "+contextSSC.getSSCApplicationVersionId()+" from "+conn.getBaseUrl());
		conn.updateApplicationVersionIssueSearchOptions(contextSSC.getSSCApplicationVersionId(), getIssueSearchOptions());
		int start=0;
		int count=50;
		while ( start < count ) {
			LOG.info("[SSC] Loading next set of data");
			WebTarget resource = conn.getBaseResource()
					.path("/api/v1/projectVersions/{SSCApplicationVersionId}/issues")
					.queryParam("qm", "issues")
					.queryParam("limit", "50")
					.queryParam("start", start)
					.resolveTemplate("SSCApplicationVersionId", contextSSC.getSSCApplicationVersionId());
			if ( StringUtils.isNotBlank(filterParamValue) ) {
				resource = resource.queryParam("q", filterParamValue);
			}
			LOG.debug("[SSC] Retrieving vulnerabilities from "+resource);
			JSONMap data = conn.executeRequest(HttpMethod.GET, resource, JSONMap.class);
			count = SpringExpressionUtil.evaluateExpression(data, EXPR_COUNT, Integer.class);
			List<?> vulnerabilitiesArray = SpringExpressionUtil.evaluateExpression(data, getRootExpression(), List.class);
			start += vulnerabilitiesArray.size();
			for ( int i = 0 ; i < vulnerabilitiesArray.size() ; i++ ) {
				Object vuln = vulnerabilitiesArray.get(i);
				if ( LOG.isTraceEnabled() ) {
					//LOG.trace("[SSC] Processing vulnerability "+vuln.optString("vulnId"));
				}
				contextCurrentVulnerability.setCurrentVulnerability(vuln);
				// We ignore the boolean result as we want to continue processing next vulnerabilities
				processor.process(Phase.PROCESS, context);
				contextCurrentVulnerability.setCurrentVulnerability(null);
			}
		}
		return processor.process(Phase.POST_PROCESS, context);
	}
	
	public SimpleExpression getRootExpression() {
		return rootExpression;
	}

	public void setRootExpression(SimpleExpression rootExpression) {
		this.rootExpression = rootExpression;
	}

	public IProcessor getVulnerabilityProcessor() {
		return vulnerabilityProcessor;
	}

	public void setVulnerabilityProcessor(IProcessor vulnerabilityProcessor) {
		this.vulnerabilityProcessor = vulnerabilityProcessor;
	}

	public IssueSearchOptions getIssueSearchOptions() {
		return issueSearchOptions;
	}

	
}
