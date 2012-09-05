/**
 * The contents of this file are subject to the OpenMRS Public License
 * Version 1.0 (the "License"); you may not use this file except in
 * compliance with the License. You may obtain a copy of the License at
 * http://license.openmrs.org
 *
 * Software distributed under the License is distributed on an "AS IS"
 * basis, WITHOUT WARRANTY OF ANY KIND, either express or implied. See the
 * License for the specific language governing rights and limitations
 * under the License.
 *
 * Copyright (C) OpenMRS, LLC.  All Rights Reserved.
 */
package org.openmrs.module.conceptpubsub.api.impl;

import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.apache.commons.lang.StringUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.hibernate.Criteria;
import org.hibernate.Session;
import org.hibernate.criterion.Order;
import org.openmrs.Concept;
import org.openmrs.ConceptMap;
import org.openmrs.ConceptSource;
import org.openmrs.GlobalProperty;
import org.openmrs.ImplementationId;
import org.openmrs.api.APIException;
import org.openmrs.api.context.Context;
import org.openmrs.api.impl.BaseOpenmrsService;
import org.openmrs.module.conceptpubsub.ConceptPubSub;
import org.openmrs.module.conceptpubsub.api.ConceptPubSubService;
import org.openmrs.module.conceptpubsub.api.adapter.ConceptAdapter;
import org.openmrs.module.conceptpubsub.api.adapter.ConceptAdapterPre19;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

/**
 * The main service of the module.
 */
public class ConceptPubSubServiceImpl extends BaseOpenmrsService implements ConceptPubSubService {
	
	protected final Log log = LogFactory.getLog(getClass());
	
	protected static final int BATCH_SIZE = 1000;
	
	private ConceptAdapter conceptAdapter;
	
	@Autowired
	Session session;
	
	public ConceptPubSubServiceImpl() {
		conceptAdapter = new ConceptAdapterPre19();
	}
	
	public void setConceptAdapter(ConceptAdapter adapter) {
		this.conceptAdapter = adapter;
	}
	
	/**
	 * Allows to iterate over concepts in batches.
	 * 
	 * @param firstResult
	 * @param maxResults
	 * @return the list of concepts
	 */
	@Transactional(readOnly = true)
	public List<Concept> getConcepts(final int firstResult, final int maxResults) {
		final Criteria criteria = session.createCriteria(Concept.class);
		criteria.addOrder(Order.asc("conceptId"));
		criteria.setMaxResults(maxResults);
		criteria.setFirstResult(firstResult);
		
		@SuppressWarnings("unchecked")
		final List<Concept> list = criteria.list();
		return list;
	}
	
	@Override
	@Transactional
	public ConceptSource createLocalSourceFromImplementationId() {
		ImplementationId implementationId = Context.getAdministrationService().getImplementationId();
		
		if (implementationId == null) {
			throw new APIException("Implementation id is not set");
		}
		
		final ConceptSource source = new ConceptSource();
		source.setName(implementationId.getImplementationId() + ConceptPubSub.SELF_SOURCE_NAME_POSTFIX);
		source.setDescription(ConceptPubSub.SELF_SOURCE_DESCRIPTION_PREFIX + implementationId.getImplementationId());
		
		Context.getConceptService().saveConceptSource(source);
		
		Context.getAdministrationService().saveGlobalProperty(
		    new GlobalProperty(ConceptPubSub.SELF_SOURCE_UUID_GP, source.getUuid()));
		
		return source;
	}
	
	/**
	 * @see org.openmrs.module.conceptpubsub.api.ConceptPubSubService#getLocalSource()
	 */
	@Override
	@Transactional(readOnly = true)
	public ConceptSource getLocalSource() {
		final String sourceUuid = Context.getAdministrationService()
		        .getGlobalProperty(ConceptPubSub.SELF_SOURCE_UUID_GP, "");
		
		if (StringUtils.isEmpty(sourceUuid)) {
			throw new APIException("Self concept source is not set in the " + ConceptPubSub.SELF_SOURCE_UUID_GP
			        + " global property. Call createLocalSourceFromImplementationId to have it set automatically.");
		} else {
			final ConceptSource source = Context.getConceptService().getConceptSourceByUuid(sourceUuid);
			
			if (source == null) {
				throw new APIException("Self concept source [" + sourceUuid + "] set in the "
				        + ConceptPubSub.SELF_SOURCE_UUID_GP + " global property does not exist. Set the global property to "
				        + "an existing concept source.");
			}
			
			return source;
		}
	}
	
	/**
	 * @see org.openmrs.module.conceptpubsub.api.ConceptPubSubService#addLocalMappingToConcept(org.openmrs.Concept)
	 */
	@Override
	@Transactional
	public void addLocalMappingToConcept(final Concept concept) {
		final ConceptSource localSource = getLocalSource();
		conceptAdapter.addMappingToConcept(concept, localSource);
	}
	
	@Override
	@Transactional
	public void addLocalMappingsToAllConcepts() {
		int position = 0;
		List<Concept> concepts = getConcepts(position, BATCH_SIZE);
		while (true) {
			for (Concept concept : concepts) {
				addLocalMappingToConcept(concept);
			}
			
			if (concepts.size() == BATCH_SIZE) {
				position += BATCH_SIZE;
				concepts = getConcepts(position, BATCH_SIZE);
			} else {
				break;
			}
		}
	}
	
	@Override
	@Transactional(readOnly = true)
	public Set<ConceptSource> getSubscribedSources() {
		final String sourceUuidsList = Context.getAdministrationService().getGlobalProperty(
		    ConceptPubSub.SUBSCRIBED_TO_SOURCE_UUIDS_GP, "");
		final String[] sourceUuids = sourceUuidsList.split(",");
		
		if (sourceUuids.length == 0) {
			return Collections.emptySet();
		}
		
		final Set<ConceptSource> subscribedToSources = new HashSet<ConceptSource>();
		for (String sourceUuid : sourceUuids) {
			sourceUuid = sourceUuid.trim();
			final ConceptSource source = Context.getConceptService().getConceptSourceByUuid(sourceUuid);
			subscribedToSources.add(source);
		}
		return subscribedToSources;
	}
	
	@Override
	public boolean isLocalConcept(final Concept concept) {
		final Set<ConceptSource> subscribedSources = getSubscribedSources();
		
		for (ConceptMap map : concept.getConceptMappings()) {
			if (subscribedSources.contains(map.getSource())) {
				return false;
			}
		}
		
		return true;
	}
	
	@Override
	public Concept getConcept(final String mapping) {
		if (StringUtils.isBlank(mapping)) {
			throw new IllegalArgumentException("Mapping must not be blank");
		}
		
		final String[] split = mapping.split(":");
		if (split.length == 1) {
			try {
				final Integer id = Integer.valueOf(split[0]);
				return getConcept(id);
			}
			catch (NumberFormatException e) {
				throw new IllegalArgumentException("Mapping '" + mapping + "' has format 'id'. The id '" + split[0]
				        + "' must be an integer.", e);
			}
		} else if (split.length == 2) {
			final String source = split[0];
			final String code = split[1];
			
			try {
				Integer.parseInt(code);
			}
			catch (NumberFormatException e) {
				throw new IllegalArgumentException("Mapping '" + mapping + "' has format 'source:id'. The id '" + split[0]
				        + "' must be an integer.", e);
			}
			
			return Context.getConceptService().getConceptByMapping(source, code);
		} else {
			throw new IllegalArgumentException("Mapping '" + mapping + "' must contain only one ':'");
		}
	}
	
	@Override
	public Concept getConcept(final Integer id) {
		return Context.getConceptService().getConcept(id);
	}
}