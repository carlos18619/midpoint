/*
 * Copyright (c) 2011 Evolveum
 * 
 * The contents of this file are subject to the terms
 * of the Common Development and Distribution License
 * (the License). You may not use this file except in
 * compliance with the License.
 * 
 * You can obtain a copy of the License at
 * http://www.opensource.org/licenses/cddl1 or
 * CDDLv1.0.txt file in the source code distribution.
 * See the License for the specific language governing
 * permission and limitations under the License.
 * 
 * If applicable, add the following below the CDDL Header,
 * with the fields enclosed by brackets [] replaced by
 * your own identifying information:
 * Portions Copyrighted 2011 [name of copyright owner]
 */
package com.evolveum.midpoint.provisioning.impl;

import javax.xml.namespace.QName;

import org.apache.commons.lang.NotImplementedException;
import org.springframework.stereotype.Service;

import com.evolveum.midpoint.common.DebugUtil;
import com.evolveum.midpoint.common.result.OperationResult;
import com.evolveum.midpoint.provisioning.api.ProvisioningService;
import com.evolveum.midpoint.provisioning.api.ResultHandler;
import com.evolveum.midpoint.provisioning.ucf.api.GenericFrameworkException;
import com.evolveum.midpoint.repo.api.RepositoryService;
import com.evolveum.midpoint.schema.exception.CommunicationException;
import com.evolveum.midpoint.schema.exception.ObjectAlreadyExistsException;
import com.evolveum.midpoint.schema.exception.ObjectNotFoundException;
import com.evolveum.midpoint.schema.exception.SchemaException;
import com.evolveum.midpoint.xml.ns._public.common.common_1.ObjectListType;
import com.evolveum.midpoint.xml.ns._public.common.common_1.ObjectModificationType;
import com.evolveum.midpoint.xml.ns._public.common.common_1.ObjectType;
import com.evolveum.midpoint.xml.ns._public.common.common_1.PagingType;
import com.evolveum.midpoint.xml.ns._public.common.common_1.PropertyAvailableValuesListType;
import com.evolveum.midpoint.xml.ns._public.common.common_1.PropertyReferenceListType;
import com.evolveum.midpoint.xml.ns._public.common.common_1.QueryType;
import com.evolveum.midpoint.xml.ns._public.common.common_1.ResourceObjectShadowType;
import com.evolveum.midpoint.xml.ns._public.common.common_1.ResourceType;
import com.evolveum.midpoint.xml.ns._public.common.common_1.ScriptsType;
import com.evolveum.midpoint.xml.ns._public.common.common_1.TaskStatusType;
import com.evolveum.midpoint.xml.schema.SchemaConstants;

/**
 * Implementation of provisioning service.
 * 
 * It is just a "dispatcher" that routes interface calls to appropriate places.
 * E.g. the operations regarding resource definitions are routed directly to the
 * repository, operations of shadow objects are routed to the shadow cache and
 * so on.
 * 
 * WORK IN PROGRESS
 * 
 * There be dragons. Beware the dog. Do not trespass.
 * 
 * @author Radovan Semancik
 */
@Service(value = "provisioningService")
public class ProvisioningServiceImpl implements ProvisioningService {

	private ShadowCache shadowCache;
	private RepositoryService repositoryService;

	public ShadowCache getShadowCache() {
		return shadowCache;
	}

	public void setShadowCache(ShadowCache shadowCache) {
		this.shadowCache = shadowCache;
	}

	/**
	 * Get the value of repositoryService.
	 * 
	 * @return the value of repositoryService
	 */
	public RepositoryService getRepositoryService() {
		return repositoryService;
	}

	/**
	 * Set the value of repositoryService
	 * 
	 * Expected to be injected.
	 * 
	 * @param repositoryService
	 *            new value of repositoryService
	 */
	public void setRepositoryService(RepositoryService repositoryService) {
		this.repositoryService = repositoryService;
	}

	@Override
	public ObjectType getObject(String oid, PropertyReferenceListType resolve, OperationResult parentResult)
			throws ObjectNotFoundException, CommunicationException, SchemaException {

		// Result type for this operation
		OperationResult result = parentResult.createSubresult(ProvisioningService.class.getName()
				+ ".getObject");
		result.addParam("oid", oid);
		result.addParam("resolve", resolve);
		result.addContext(OperationResult.CONTEXT_IMPLEMENTATION_CLASS, ProvisioningServiceImpl.class);

		ObjectType repositoryObject = null;

		try {
			repositoryObject = getRepositoryService().getObject(oid, resolve, result);
			
		} catch (ObjectNotFoundException e) {
			result.record(e);
			throw e;
		}

		if (repositoryObject instanceof ResourceObjectShadowType) {
			// ResourceObjectShadowType shadow =
			// (ResourceObjectShadowType)object;
			// TODO: optimization needed: avoid multiple "gets" of the same
			// object

			ResourceObjectShadowType shadow = null;
			try {
				shadow = getShadowCache().getShadow(oid, (ResourceObjectShadowType) repositoryObject, result);
				
			} catch (ObjectNotFoundException e) {
				result.record(e);
				throw e;
			} catch (CommunicationException e) {
				result.record(e);
				throw e;
			} catch (SchemaException e) {
				result.record(e);
				throw e;
			}

			// TODO: object resolving
			return shadow;
		} else {
			return repositoryObject;
		}

	}

	@Override
	public String addObject(ObjectType object, ScriptsType scripts, OperationResult parentResult)
			throws ObjectAlreadyExistsException, SchemaException, CommunicationException {
		// TODO

		OperationResult result = parentResult.createSubresult(ProvisioningService.class.getName()
				+ ".addObject");
		result.addParam("object", object);
		result.addParam("scripts", scripts);
		result.addContext(OperationResult.CONTEXT_IMPLEMENTATION_CLASS, ProvisioningServiceImpl.class);

		String addedShadow = null;

		try {
			addedShadow = getShadowCache().addShadow(object, scripts, null, parentResult);
		} catch (GenericFrameworkException ex) {
			result.recordFatalError("Failed to add shadow object: " + ex.getMessage(), ex);
			throw new CommunicationException(ex.getMessage(), ex);
		}
		return addedShadow;
	}

	@Override
	public void synchronize(String oid, OperationResult parentResult) {
		// TODO Auto-generated method stub
		throw new NotImplementedException();
	}

	@Override
	public ObjectListType listObjects(Class<? extends ObjectType> objectType, PagingType paging,
			OperationResult parentResult) {

		// Result type for this operation
		OperationResult result = parentResult.createSubresult(ProvisioningService.class.getName()
				+ ".listObjects");
		result.addParam("objectType", objectType);
		result.addParam("paging", paging);
		result.addContext(OperationResult.CONTEXT_IMPLEMENTATION_CLASS, ProvisioningServiceImpl.class);

		ObjectListType objListType = null;
		
		if (ResourceObjectShadowType.class.isAssignableFrom(objectType)) {
			// Listing of shadows is not supported because this operation does
			// not specify resource
			// to search. Maybe we need another operation for this.
			
			throw new NotImplementedException("Listing of shadows is not supported");

		} else {
			// TODO: delegate to repository
			objListType = getRepositoryService().listObjects(objectType, paging, parentResult);
		}
		return objListType;

	}

	@Override
	public ObjectListType searchObjects(QueryType query, PagingType paging, OperationResult parentResult)
			throws SchemaException {
		// TODO Auto-generated method stub
		throw new NotImplementedException();
	}

	@Override
	public void modifyObject(ObjectModificationType objectChange, ScriptsType scripts,
			OperationResult parentResult) throws ObjectNotFoundException, SchemaException, CommunicationException {
		if (objectChange == null || objectChange.getOid() == null) {
			throw new IllegalArgumentException("Object change or object change oid cannot be null");
		}

		ObjectType objectType = getRepositoryService().getObject(objectChange.getOid(),
				new PropertyReferenceListType(), parentResult);

		try {
			getShadowCache().modifyShadow(objectType, null, objectChange, scripts, parentResult);
		} catch (CommunicationException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		} catch (GenericFrameworkException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}

		// TODO Auto-generated method stub
	}

	@Override
	public void deleteObject(String oid, ScriptsType scripts, OperationResult parentResult)
			throws ObjectNotFoundException, CommunicationException, SchemaException {
		// TODO Auto-generated method stub
		OperationResult result = parentResult.createSubresult(ProvisioningService.class.getName()
				+ ".deleteObject");
		result.addParam("oid", oid);
		result.addParam("scripts", scripts);
		result.addContext(OperationResult.CONTEXT_IMPLEMENTATION_CLASS, ProvisioningServiceImpl.class);

		ObjectType objectType = null;
		try {
			objectType = getRepositoryService().getObject(oid, new PropertyReferenceListType(), parentResult);
		} catch (SchemaException e) {
			throw new ObjectNotFoundException(e.getMessage());
		}

		try {
			getShadowCache().deleteShadow(objectType, null, parentResult);
		} catch (CommunicationException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		} catch (GenericFrameworkException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		} catch (SchemaException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}

	}

	@Override
	public PropertyAvailableValuesListType getPropertyAvailableValues(String oid,
			PropertyReferenceListType properties, OperationResult parentResult)
			throws ObjectNotFoundException {
		// TODO Auto-generated method stub
		throw new NotImplementedException();
	}

	@Override
	public OperationResult testResource(String resourceOid) throws ObjectNotFoundException {
		OperationResult parentResult = new OperationResult(ProvisioningService.class.getName()+".testResource");
		parentResult.addParam("resourceOid", resourceOid);
		parentResult.addContext(OperationResult.CONTEXT_IMPLEMENTATION_CLASS, ProvisioningServiceImpl.class);
		if (resourceOid == null){
			throw new IllegalArgumentException("Resource OID to test is null.");
		}
		
		OperationResult result = null;
		
		try{
			ObjectType objectType = getRepositoryService().getObject(resourceOid, new PropertyReferenceListType(), parentResult);
			if (objectType instanceof ResourceType){
				ResourceType resourceType = (ResourceType) objectType;
				result = getShadowCache().testConnection(resourceType);
			} else{
				throw new IllegalArgumentException("Object with oid is not resource. OID: "+resourceOid);
			}		
		} catch (ObjectNotFoundException ex){
			throw new ObjectNotFoundException("Object with OID "+resourceOid+" not found");
		} catch (SchemaException ex){
			throw new IllegalArgumentException(ex.getMessage(), ex);
		}
		return result;
	}

	@Override
	public ObjectListType listResourceObjects(String resourceOid, QName objectType, PagingType paging,
			OperationResult parentResult) {
		// TODO Auto-generated method stub
		throw new NotImplementedException();
	}

	@Override
	public void searchObjectsIterative(QueryType query, PagingType paging, ResultHandler handler, OperationResult parentResult)
			throws SchemaException {
		// TODO Auto-generated method stub
		throw new NotImplementedException();
	}

}
