package com.dotcms.publisher.business;

import static com.dotcms.util.CollectionsUtils.map;

import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.quartz.JobExecutionContext;
import org.quartz.JobExecutionException;
import org.quartz.StatefulJob;

import com.dotcms.enterprise.publishing.PublishDateUpdater;
import com.dotcms.enterprise.publishing.staticpublishing.AWSS3Publisher;
import com.dotcms.publisher.business.PublishAuditStatus.Status;
import com.dotcms.publisher.endpoint.bean.PublishingEndPoint;
import com.dotcms.publisher.endpoint.business.PublishingEndPointAPI;
import com.dotcms.publisher.environment.bean.Environment;
import com.dotcms.publisher.environment.business.EnvironmentAPI;
import com.dotcms.publisher.pusher.PushPublisher;
import com.dotcms.publisher.pusher.PushPublisherConfig;
import com.dotcms.publisher.util.PublisherUtil;
import com.dotcms.publishing.DotPublishingException;
import com.dotcms.publishing.IPublisher;
import com.dotcms.publishing.Publisher;
import com.dotcms.publishing.PublisherConfig;
import com.dotcms.repackage.com.google.common.collect.Maps;
import com.dotcms.repackage.com.google.common.collect.Sets;
import com.dotcms.repackage.javax.ws.rs.client.Client;
import com.dotcms.repackage.javax.ws.rs.client.WebTarget;
import com.dotcms.repackage.org.apache.log4j.MDC;
import com.dotcms.rest.RestClientBuilder;
import com.dotmarketing.business.APILocator;
import com.dotmarketing.db.DbConnectionFactory;
import com.dotmarketing.db.HibernateUtil;
import com.dotmarketing.exception.DotDataException;
import com.dotmarketing.exception.DotHibernateException;
import com.dotmarketing.util.Config;
import com.dotmarketing.util.Logger;
import com.dotmarketing.util.PushPublishLogger;

/**
 * This job is in charge of performing two actions:
 * <ol>
 * <li><b>Bundle status update:</b> Each bundle is associated to an environment,
 * which contains one or more end-points. This job will connect to one or all of
 * them to verify the deployment status of the bundle in order to update its
 * status in the sender server. Examples of status can be:
 * <ul>
 * <li><code>Success</code></li>
 * <li><code>Bundle sent</code></li>
 * <li><code>Failed to Publish</code></li>
 * <li><code>Failed to send to all environments</code></li>
 * <li>etc.</li>
 * </ul>
 * Please see the {@link Status} class to see all the available status options.
 * </li>
 * <li><code>Pending bundle push:</code> Besides auditing the different bundles
 * that are being sent, this job will take the bundles that are in the
 * publishing queue, identifies its assets (i.e., pages, contentlets, folders,
 * etc.) and sends them to publishing mechanism.</li>
 * </ol>
 * 
 * @author Alberto
 * @since Oct 5, 2012
 *
 */
public class PublisherQueueJob implements StatefulJob {

	private static final String BUNDLE_ID = "BundleId";

	public static final Integer MAX_NUM_TRIES = Config.getIntProperty("PUBLISHER_QUEUE_MAX_TRIES", 3);

	private PublishAuditAPI pubAuditAPI = PublishAuditAPI.getInstance();
	private PublishingEndPointAPI endpointAPI = APILocator.getPublisherEndPointAPI();
	private PublisherAPI pubAPI = PublisherAPI.getInstance();
    private EnvironmentAPI environmentAPI = APILocator.getEnvironmentAPI();
    private PublishingEndPointAPI publisherEndPointAPI = APILocator.getPublisherEndPointAPI();

	/**
	 * Reads from the publishing queue table and depending of the publish date
	 * will send a bundle to publish
	 * ({@link com.dotcms.publishing.PublisherAPI#publish(com.dotcms.publishing.PublisherConfig)}).
	 *
	 * @param jobExecution
	 *            - Context Containing the current job context information (the data).
	 * @throws JobExecutionException
	 *             An exception occurred while executing the job.
	 * @see PublisherAPI
	 * @see PublisherAPIImpl
	 */
	public void execute(JobExecutionContext jobExecutionContext) throws JobExecutionException {
		try {
			Logger.debug(PublisherQueueJob.class, "Started PublishQueue Job - check for publish dates");
			PublishDateUpdater.updatePublishExpireDates(jobExecutionContext.getFireTime());
			Logger.debug(PublisherQueueJob.class, "Finished PublishQueue Job - check for publish/expire dates");

			Logger.debug(PublisherQueueJob.class, "Started PublishQueue Job - Audit update");
			updateAuditStatus();
			Logger.debug(PublisherQueueJob.class, "Finished PublishQueue Job - Audit update");

			//Verify if we have endpoints where to send the bundles
			List<PublishingEndPoint> endpoints = endpointAPI.getEnabledReceivingEndPoints();
			if ( endpoints != null && endpoints.size() > 0 ) {
				Logger.debug(PublisherQueueJob.class, "Started PublishQueue Job");

				List<Map<String, Object>> bundles = pubAPI.getQueueBundleIdsToProcess();
				List<PublishQueueElement> tempBundleContents;
				PublishAuditStatus status;
				PublishAuditHistory historyPojo;
				String tempBundleId;

				for ( Map<String, Object> bundle : bundles ) {
					Date publishDate = (Date) bundle.get("publish_date");
					if ( publishDate.before(new Date()) ) {
						tempBundleId = (String) bundle.get("bundle_id");
						MDC.put(BUNDLE_ID, BUNDLE_ID + "=" + tempBundleId);

						try {
							PushPublishLogger.log(this.getClass(), "Pre-publish work started.");
							tempBundleContents = pubAPI.getQueueElementsByBundleId(tempBundleId);

							//Setting Audit objects
							//History
							historyPojo = new PublishAuditHistory();
							//Retriving assets
							Map<String, String> assets = new HashMap<String, String>();
							List<PublishQueueElement> assetsToPublish = new ArrayList<PublishQueueElement>();

							for ( PublishQueueElement c : tempBundleContents ) {
								assets.put(c.getAsset(), c.getType());
								assetsToPublish.add(c);
							}
							historyPojo.setAssets(assets);

							PublisherConfig pconf = new PushPublisherConfig();
							pconf.setAssets(assetsToPublish);

							//Status
							status = new PublishAuditStatus(tempBundleId);
							status.setStatusPojo(historyPojo);

							//Insert in Audit table
							pubAuditAPI.insertPublishAuditStatus(status);

							//Queries creation
							pconf.setLuceneQueries(PublisherUtil.prepareQueries(tempBundleContents));
							pconf.setId(tempBundleId);
							pconf.setUser(APILocator.getUserAPI().getSystemUser());
							pconf.setStartDate(new Date());
							pconf.runNow();
							pconf.setPublishers(new ArrayList<>(getPublishersForBundle(tempBundleId)));

							if ( Integer.parseInt(bundle.get("operation").toString()) == PublisherAPI.ADD_OR_UPDATE_ELEMENT ) {
								pconf.setOperation(PushPublisherConfig.Operation.PUBLISH);
							} else {
								pconf.setOperation(PushPublisherConfig.Operation.UNPUBLISH);
							}

							pconf = setUpConfigForPublisher(pconf);
							PushPublishLogger.log(this.getClass(), "Pre-publish work complete.");

							try {
								APILocator.getPublisherAPI().publish(pconf);
							} catch (DotPublishingException e) {
								/*
								If we are getting errors creating the bundle we should stop trying to publish it, this is not just a connection error,
								there is something wrong with a bundler or creating the bundle.
								 */
								Logger.error(PublisherQueueJob.class, "Unable to publish Bundle: " + e.getMessage(), e);
								PushPublishLogger.log(this.getClass(), "Status Update: Failed to bundle");
								pubAuditAPI.updatePublishAuditStatus(pconf.getId(), PublishAuditStatus.Status.FAILED_TO_BUNDLE, historyPojo);
								pubAPI.deleteElementsFromPublishQueueTable(pconf.getId());
							}
						} finally {
							MDC.remove(BUNDLE_ID);
						}
					}
				}
				Logger.debug(PublisherQueueJob.class, "Finished PublishQueue Job");
			}
		} catch (Exception e) {
			Logger.error(PublisherQueueJob.class, e.getMessage(), e);
		} finally {
			try {
				HibernateUtil.closeSession();
			} catch (DotHibernateException e) {
				Logger.warn(this, "exception while calling HibernateUtil.closeSession()", e);
			} finally {
				DbConnectionFactory.closeConnection();
			}
		}
	}

	/**
	 * Updates the status of a Bundle in the job queue. This method also
	 * verifies and limits the number of times a Bundle is allowed to attempt to
	 * be published in case of errors.
	 *
	 * @throws DotPublisherException
	 *             An error occurred when modifying the Publishing status,
	 *             retrieving status information or removing the current bundle
	 *             from the Publish queue table.
	 * @throws DotDataException
	 *             An error occurred when retrieving the end-points from the
	 *             database.
	 */
	private void updateAuditStatus() throws DotPublisherException, DotDataException {
		Client client = RestClientBuilder.newClient();
		List<PublishAuditStatus> pendingBundleAudits = pubAuditAPI.getPendingPublishAuditStatus();
		// For each bundle
 		for ( PublishAuditStatus bundleAudit : pendingBundleAudits ) {
			MDC.put(BUNDLE_ID, BUNDLE_ID + "=" + bundleAudit.getBundleId());

			try {
				PublishAuditHistory localHistory = bundleAudit.getStatusPojo();
				Date publishStart = null;
				Date publishEnd = null;
				//There is no need to keep checking after MAX_NUM_TRIES.
				if ( localHistory.getNumTries() <= (MAX_NUM_TRIES + 1) ) {
					int countGroupOk = 0;
					int countGroupPublishing = 0;
					int countGroupFailed = 0;

					Map<String, Map<String, EndpointDetail>> endpointsMap = localHistory.getEndpointsMap();
					Map<String, Map<String, EndpointDetail>> endpointTrackingMap = new HashMap<String, Map<String, EndpointDetail>>();
					// For each group (environment)
					for ( String groupID : endpointsMap.keySet() ) {
						Map<String, EndpointDetail> endpointsGroup = endpointsMap.get(groupID);
						// For each end-point (server) in the group
						for ( String endpointID : endpointsGroup.keySet() ) {
							PublishingEndPoint targetEndpoint = endpointAPI.findEndPointById(endpointID);
							if ( targetEndpoint != null && !targetEndpoint.isSending() ) {

								// Don't poll status for static publishing
								if (!AWSS3Publisher.PROTOCOL_AWS_S3.equalsIgnoreCase(targetEndpoint.getProtocol())) {
									WebTarget webTarget = client.target(targetEndpoint.toURL() + "/api/auditPublishing");
									try {
										// Try to get the status of the remote end-points to
										// update the local history
										PublishAuditHistory remoteHistory =
												PublishAuditHistory.getObjectFromString(
														webTarget
																.path("get")
																.path(bundleAudit.getBundleId()).request().get(String.class));
										if (remoteHistory != null) {
											publishStart = remoteHistory.getPublishStart();
											publishEnd = remoteHistory.getPublishEnd();
											endpointTrackingMap.putAll(remoteHistory.getEndpointsMap());
											for (String remoteGroupId : remoteHistory.getEndpointsMap().keySet()) {
												Map<String, EndpointDetail> remoteGroup = endpointTrackingMap
														.get(remoteGroupId);
												for (String remoteEndpointId : remoteGroup.keySet()) {
													EndpointDetail remoteDetail = remoteGroup.get(remoteEndpointId);
													localHistory.addOrUpdateEndpoint(groupID, endpointID, remoteDetail);
												}
											}
										}
									} catch (Exception e) {
										// An error occurred when retrieving the end-point's audit info. 
										// Usually caused by a network problem.
										Logger.error(PublisherQueueJob.class, e.getMessage(), e);
										String failedAuditUpdate = "failed-remote-group-" + System.currentTimeMillis();
										EndpointDetail detail = new EndpointDetail();
										detail.setStatus(Status.FAILED_TO_PUBLISH.getCode());
										endpointTrackingMap.put(failedAuditUpdate, map(failedAuditUpdate, detail));
									}
								} else {
									PublishAuditStatus pas = pubAuditAPI.getPublishAuditStatus(bundleAudit.getBundleId());
									if (pas != null && pas.getStatus() == PublishAuditStatus.Status.BUNDLE_SENT_SUCCESSFULLY) {
										countGroupFailed--;
									} else if (pas != null && pas.getStatus().name().toLowerCase().startsWith("failed") && localHistory.getNumTries() >= MAX_NUM_TRIES) {
										countGroupFailed++;
									}
								}
							}
						}
					}

					// Check the push status in all groups (environments) to update the
					// publish audit table with the latest info
					for ( String groupId : endpointTrackingMap.keySet() ) {
						Map<String, EndpointDetail> group = endpointTrackingMap.get(groupId);
						boolean isGroupOk = false;
						boolean isGroupPublishing = false;
						boolean isGroupFailed = false;
						for ( String endpoint : group.keySet() ) {
							EndpointDetail detail = group.get(endpoint);
							if ( detail.getStatus() == Status.SUCCESS.getCode() ) {
								isGroupOk = true;
							} else if ( detail.getStatus() == Status.PUBLISHING_BUNDLE
									.getCode() ) {
								isGroupPublishing = true;
							} else if ( detail.getStatus() == Status.FAILED_TO_PUBLISH
									.getCode() ) {
								isGroupFailed = true;
							}
						}
						if ( isGroupOk ) {
							countGroupOk++;
						}
						if ( isGroupPublishing ) {
							countGroupPublishing++;
						}
						if ( isGroupFailed ) {
							countGroupFailed++;
						}
					}
					localHistory.setPublishStart(publishStart);
					localHistory.setPublishEnd(publishEnd);
					if ( localHistory.getNumTries() >= MAX_NUM_TRIES && (countGroupFailed > 0 || countGroupPublishing > 0) ) {
						// If bundle cannot be installed after [MAX_NUM_TRIES] tries
						// and some groups could not be published
						List<Environment> environments = APILocator.getEnvironmentAPI().findEnvironmentsByBundleId(bundleAudit.getBundleId());
						for ( Environment environment : environments ) {
							APILocator.getPushedAssetsAPI().deletePushedAssets(bundleAudit.getBundleId(), environment.getId());
						}
						PushPublishLogger.log(this.getClass(), "Status Update: Failed to publish");
						pubAuditAPI.updatePublishAuditStatus(bundleAudit.getBundleId(),
								PublishAuditStatus.Status.FAILED_TO_PUBLISH,
								localHistory);
						pubAPI.deleteElementsFromPublishQueueTable(bundleAudit.getBundleId());
					} else if ( countGroupFailed > 0 && countGroupOk > 0 ) {
						// If bundle was installed in some groups only
						pubAuditAPI.updatePublishAuditStatus(bundleAudit.getBundleId(),
								PublishAuditStatus.Status.FAILED_TO_SEND_TO_SOME_GROUPS,
								localHistory);
					} else if ( countGroupFailed == endpointTrackingMap.size() ) {
						// If bundle cannot be installed in all groups
						pubAuditAPI.updatePublishAuditStatus(bundleAudit.getBundleId(),
								PublishAuditStatus.Status.FAILED_TO_SEND_TO_ALL_GROUPS,
								localHistory);
					} else if ( countGroupOk == endpointTrackingMap.size() ) {
						// If bundle was installed in all groups
						PushPublishLogger.log(this.getClass(), "Status Update: Success");
						pubAuditAPI.updatePublishAuditStatus(bundleAudit.getBundleId(),
								PublishAuditStatus.Status.SUCCESS,
								localHistory);
						pubAPI.deleteElementsFromPublishQueueTable(bundleAudit.getBundleId());
					} else if ( countGroupPublishing == endpointTrackingMap.size() ) {
						// If bundle is still publishing in all groups
						pubAuditAPI.updatePublishAuditStatus(bundleAudit.getBundleId(),
								PublishAuditStatus.Status.PUBLISHING_BUNDLE,
								localHistory);
					} else {
						// Otherwise, just keep trying to publish the bundle
						pubAuditAPI.updatePublishAuditStatus(bundleAudit.getBundleId(),
								PublishAuditStatus.Status.WAITING_FOR_PUBLISHING,
								localHistory);
					}
				} else {
					//We delete the Publish Queue.
					pubAPI.deleteElementsFromPublishQueueTable(bundleAudit.getBundleId());
				}
			} finally {
				MDC.remove(BUNDLE_ID);
			}
		}
	}

	/**
	 * Get the Publisher needed depending on the protocol of the end-points of
	 * the bundle.
	 *
	 * @param bundleId
	 *            - The Id of the generated bundle.
	 * @return The {@link Publisher} classes for the specified bundle.
	 */
    private Set<Class<?>> getPublishersForBundle(String bundleId){

        Set<Class<?>> publishersClasses = new HashSet<>();

	    try{
            Map<String, Class<? extends IPublisher>> protocolPublisherMap = Maps.newConcurrentMap();
            //TODO: for OSGI we need to get this list from implementations of IPublisher or something else.
            Set<Class<?>> publishers = Sets.newHashSet(PushPublisher.class, AWSS3Publisher.class);

            //Fill protocolPublisherMap with protocol -> publisher.
            for (Class publisher : publishers) {
                Publisher p = (Publisher)publisher.newInstance();
                for (String protocol : p.getProtocols()) {
                    protocolPublisherMap.put(protocol, publisher);
                }
            }

            //For each environment in the bundle we need to get the end-points.
            List<Environment> environments = this.environmentAPI.findEnvironmentsByBundleId(bundleId);

            for (Environment environment : environments) {
                //For each end-point we choose if run static or dynamic process (Static = AWSS3Publisher, Dynamic = PushPublisher)
                List<PublishingEndPoint> endpoints = this.publisherEndPointAPI.findSendingEndPointsByEnvironment(environment.getId());

                //For each end-point we need include the Publisher depending on the type.
                for (PublishingEndPoint endpoint : endpoints) {
                    //Only if the end-point is enabled.
                    if (endpoint.isEnabled() && protocolPublisherMap.containsKey(endpoint.getProtocol())){
                        publishersClasses.add(protocolPublisherMap.get(endpoint.getProtocol()));

                    }
                }
            }
        } catch (Exception e){
	        Logger.error(this, "Error trying to get publishers from bundle id: " + bundleId, e);
        }

	    return publishersClasses;
    }

	/**
	 * Sends the parameter {@link PublisherConfig} to each Publisher in use to
	 * be filled with the necessary information, such as extra languages, hosts,
	 * etc.
	 *
	 * @param pconf
	 *            - The {@link PublisherConfig} object.
	 * @return
	 * @throws IllegalAccessException
	 *             The {@link Publisher} class could not be instantiated.
	 * @throws InstantiationException
	 *             The {@link Publisher} class could not be instantiated.
	 */
    private PublisherConfig setUpConfigForPublisher(PublisherConfig pconf)
        throws IllegalAccessException, InstantiationException {

        final List<Class> publishers = pconf.getPublishers();
        for (Class<?> publisher : publishers) {
            pconf = ((Publisher)publisher.newInstance()).setUpConfig(pconf);
        }

        return pconf;
    }

}
