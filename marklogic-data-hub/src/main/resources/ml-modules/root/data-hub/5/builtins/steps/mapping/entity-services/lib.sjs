'use strict';

const DataHubSingleton = require("/data-hub/5/datahub-singleton.sjs");
const datahub = DataHubSingleton.instance();
const es = require('/MarkLogic/entity-services/entity-services');
const mappingLib = require('/data-hub/5/builtins/steps/mapping/default/lib.sjs');
const sem = require("/MarkLogic/semantics.xqy");
const semPrefixes = {es: 'http://marklogic.com/entity-services#'};
const dhMappingTrace = 'DH-MAPPING';
const dhMappingTraceIsEnabled = xdmp.traceEnabled(dhMappingTrace);

const xmlMappingCollections = ['http://marklogic.com/entity-services/mapping', 'http://marklogic.com/data-hub/mappings/xml'];
const entitiesByTargetType = {};

const xsltPermissions = [
  xdmp.permission(datahub.config.FLOWOPERATORROLE,'execute'),
  xdmp.permission(datahub.config.FLOWDEVELOPERROLE,'execute'),
  xdmp.permission(datahub.config.FLOWOPERATORROLE,'read'),
  xdmp.permission(datahub.config.FLOWDEVELOPERROLE,'read')
];

function buildMappingXML(mappingJSON) {
  // Obtain all linked JSON mappings
  const relatedMappings = getRelatedMappings(mappingJSON).map((mappingDoc) => mappingDoc.toObject());
  if (dhMappingTraceIsEnabled) {
    xdmp.trace(dhMappingTrace, 'Building mapping XML');
  }
  // for each mapping build out the mapping XML
  const entityTemplates = [];
  for (let mapping of relatedMappings) {
    if (dhMappingTraceIsEnabled) {
      xdmp.trace(dhMappingTrace, `Generating template for ${mapping.targetEntityType}`);
    }
    let entity = getTargetEntity(mapping.targetEntityType);
    let entityTemplate = buildEntityMappingXML(mapping, entity);
    if (dhMappingTraceIsEnabled) {
      xdmp.trace(dhMappingTrace, `Generated template: ${entityTemplate}`);
    }
    entityTemplates.push(entityTemplate);
  }
  let entityName = getEntityName(mappingJSON.root.targetEntityType);
  // compose the final template
  let finalTemplate = `
      <m:mapping xmlns:m="http://marklogic.com/entity-services/mapping">
      ${retrieveFunctionImports()}
      ${entityTemplates.join('\n')}
      <!-- Default entity is ${entityName} -->
      <m:output>
        <m:for-each><m:select>./(element()|object-node()|array-node())</m:select>
            <m:call-template name="${entityName}" />
        </m:for-each>
      </m:output>
    </m:mapping>
  `;
  return xdmp.unquote(finalTemplate);
}

function buildMapProperties(mapping, entityModel) {
  let mapProperties = mapping.properties;
  let propertyLines = [];
  if (dhMappingTraceIsEnabled) {
    xdmp.trace(dhMappingTrace, `Building mapping properties for '${mapping.targetEntityType}' with 
    '${xdmp.describe(entityModel)}'`);
  }
  let entityName = getEntityName(mapping.targetEntityType);
  if (dhMappingTraceIsEnabled) {
    xdmp.trace(dhMappingTrace, `Using entity name: ${entityName}`);
  }
  let entityDefinition = entityModel.definitions[entityName];
  if (dhMappingTraceIsEnabled) {
    xdmp.trace(dhMappingTrace, `Using entity definition: ${entityDefinition}`);
  }
  let namespacePrefix = entityDefinition.namespacePrefix ? `${entityDefinition.namespacePrefix}:` : '';
  let requiredProps = entityDefinition.required || [entityModel.primaryKey];
  if (!requiredProps.includes(entityModel.primaryKey)) {
    requiredProps.push(entityModel.primaryKey);
  }
  let entityProperties = entityDefinition.properties;
  for (let prop in mapProperties) {
    if (mapProperties.hasOwnProperty(prop)) {
      let isRequired = requiredProps.includes(prop);
      if (!entityProperties.hasOwnProperty(prop)) {
        // TODO Can pass in a JSON object instead of a string message, but not able to reference the properties on it
        throw Error("The property '" + prop + "' is not defined by the entity model");
      }
      let dataType = entityProperties[prop].datatype;
      let isArray = false;
      if (dataType === 'array') {
        isArray = true;
        dataType = entityProperties[prop].items.datatype;
      }
      let mapProperty = mapProperties[prop];
      let propTag = namespacePrefix + prop;
      let sourcedFrom = escapeXML(mapProperty.sourcedFrom);
      let isInternalMapping = mapProperty.targetEntityType && mapProperty.properties;
      if (isInternalMapping || isArray) {
        let propLine;
        if (isInternalMapping) {
          let subEntityName = getEntityName(mapProperty.targetEntityType);
          propLine = `<${propTag} ${isArray? 'datatype="array"':''}><m:call-template name="${subEntityName}"/></${propTag}>`;
        } else {
          propLine = `<${propTag} datatype="array" xsi:type="xs:${dataType}"><m:val>.</m:val></${propTag}>`;
        }
        propertyLines.push(`<m:for-each><m:select>${sourcedFrom}</m:select>
            ${propLine}
          </m:for-each>`);
      } else {
        let propLine = `<${propTag} xsi:type="xs:${dataType}"><m:val>${sourcedFrom}</m:val></${propTag}>`;
        // If a property is required but not marked as optional, it will always be added, and then entity validation
        // will not fail because the property exists with an empty string as the value.
        propLine = `<m:optional>${propLine}</m:optional>`
        propertyLines.push(propLine);
      }
    }
  }
  return propertyLines.join('\n');
}

function getRelatedMappings(mapping, related = [mapping]) {
  // get references to sub mappings
  if (dhMappingTraceIsEnabled) {
    xdmp.trace(dhMappingTrace, `Getting related mappings for '${xdmp.describe(mapping)}'`);
  }
  let internalMappings = mapping.xpath('/properties//object-node()[exists(targetEntityType) and exists(properties)]');
  for (let internalMapping of internalMappings) {
    related.push(internalMapping);
  }
  return related;
}

function getTargetEntity(targetEntityType) {
  if (!entitiesByTargetType[targetEntityType]) {
    let entityModel = getModel(targetEntityType);
    if (fn.empty(entityModel)) {
      entityModel = fallbackLegacyEntityLookup(targetEntityType)
    }
    if (entityModel && (entityModel.constructor.name === "Document" || entityModel.constructor.name === "ObjectNode")) {
      entityModel = entityModel.toObject();
    }
    if(!entityModel) {
      datahub.debug.log({message: 'Could not find a target entity: ' + targetEntityType, type: 'error'});
      throw Error('Could not find a target entity: ' + targetEntityType);
    }
    entitiesByTargetType[targetEntityType] = entityModel;
  }
  return entitiesByTargetType[targetEntityType];
}

function retrieveFunctionImports() {
  let customImports = [];
  let shimURIs = datahub.hubUtils.queryLatest(function() {
    return cts.uris(null, null, cts.collectionQuery('http://marklogic.com/entity-services/function-metadata/compiled'));
  }, xdmp.databaseName(xdmp.modulesDatabase()));
  for (let uri of shimURIs) {
    customImports.push(`<m:use-functions href="${fn.string(uri).replace(/\.xslt?$/, '')}"/>`);
  }
  return customImports.join('\n');
}

function buildEntityMappingXML(mapping, entity) {
  let entityTitle = entity.info.title;
  let entityName = getEntityName(mapping.targetEntityType);
  let entityDefinition = entity.definitions[entityName];
  let namespacePrefix = entityDefinition.namespacePrefix;
  let entityTag = namespacePrefix ? `${namespacePrefix}:${entityName}`: entityName;
  let namespaceNode = `xmlns${namespacePrefix ? `:${namespacePrefix}`: ''}="${entityDefinition.namespace || ''}"`;
  return `
      <m:entity name="${entityName}" xmlns:m="http://marklogic.com/entity-services/mapping">
        <${entityTag} ${namespaceNode} xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance">
          ${buildMapProperties(mapping, entity)}
        </${entityTag}>
      </m:entity>`;
}

function getEntityName(targetEntityType) {
  return fn.head(fn.reverse(fn.tokenize(targetEntityType,'/')));
}

function getModel(targetEntityType) {
  return fn.head(cts.search(
      cts.andQuery([
        cts.collectionQuery('http://marklogic.com/entity-services/models'),
        cts.tripleRangeQuery(sem.iri(targetEntityType), sem.curieExpand("rdf:type"), sem.curieExpand("es:EntityType",semPrefixes), "=")
      ])));
}

function fallbackLegacyEntityLookup(targetEntityType) {
  let targetArr = targetEntityType.split('/');
  let entityName = targetArr[targetArr.length - 1];
  let tVersion = targetArr[targetArr.length - 2].split('-');
  let modelVersion = tVersion[tVersion.length - 1];
  return fn.head(mappingLib.getModel(entityName, modelVersion));
}

function escapeXML(input = '') {
  return input
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/'/g, '&apos;')
    .replace(/"/g, '&quot;')
    .replace(/{/g, '&#123;')
    .replace(/}/g, '&#125;');
}

/**
 * Validates all property mappings in the given mapping object.
 *
 * @param mapping
 * @return {{targetEntityType: *, properties: {}}}
 */
function validateMapping(mapping) {
  let validatedMapping = {
    targetEntityType : mapping.targetEntityType,
    properties : {}
  };

  Object.keys(mapping.properties).forEach(propertyName => {
    let mappedProperty = mapping.properties[propertyName];
    let sourcedFrom = mappedProperty.sourcedFrom;
    if (mappedProperty.hasOwnProperty("targetEntityType")) {
      let nestedMapping = validateMapping(mappedProperty);
      nestedMapping.sourcedFrom = sourcedFrom;
      validatedMapping.properties[propertyName] = nestedMapping;
    }
    else {
      let result = validatePropertyMapping(mapping.targetEntityType, propertyName, sourcedFrom);
      validatedMapping.properties[propertyName] = result;
    }
  });

  return validatedMapping;
}

/**
 * Validate a single property mapping by constructing a mapping consisting of just the given property mapping.
 *
 * @param targetEntityType
 * @param propertyName
 * @param sourcedFrom
 * @return {{sourcedFrom: *, errorMessage: *}|{sourcedFrom: *}}
 */
function validatePropertyMapping(targetEntityType, propertyName, sourcedFrom) {
  let mapping = {
    "targetEntityType": targetEntityType,
    "properties": {}
  };

  mapping.properties[propertyName] = {
    "sourcedFrom": sourcedFrom
  };

  try {
    let xmlMapping = buildMappingXML(fn.head(xdmp.unquote(xdmp.quote(mapping))));

    // As of trunk 10.0-20190916, mappings are being validated against entity schemas in the schema database.
    // This doesn't seem expected, as the validation will almost always fail.
    // Thus, this is not using es.mappingCompile, which does validation, and just invokes the transform instead.
    let stylesheet = xdmp.xsltInvoke("/MarkLogic/entity-services/mapping-compile.xsl", xmlMapping)

    xdmp.xsltEval(stylesheet, [], {staticCheck: true});
    // In the future, we'll capture a value here that results from applying the successfully validated mapping against a document
    return {
      sourcedFrom: sourcedFrom
    };
  } catch (e) {
    // TODO Move this into a separate function for easier testing?
    let errorMessage = e.message;
    if (e.data != null && e.data.length > 0) {
      errorMessage += ": " + e.data[0];
    }
    return {
      sourcedFrom : sourcedFrom,
      errorMessage : errorMessage
    }
  }
}

function versionIsCompatibleWithES(version = xdmp.version()) {
  let numberSensitiveCollation = 'http://marklogic.com/collation//MO';
  let isNightly = /^[0-9]+\.[0-9]+-[0-9]{8}$/.test(version);
  if (isNightly) {
    var nightlyDate = /^[0-9]+\.[0-9]+-([0-9]{8})$/.exec(version)[1];
    return fn.compare(nightlyDate, '20190824', numberSensitiveCollation) >= 0;
  }
  else {
    var major = /^([0-9]+)\..*$/.exec(version)[1];
    if (major === "9") {
      return fn.compare(version, '9.0-11', numberSensitiveCollation) >= 0;
    }
    else if (major === "10"){
      return fn.compare(version, '10.0-2', numberSensitiveCollation) >= 0;
    }
  }
  return false;
}

module.exports = {
  xsltPermissions,
  xmlMappingCollections,
  buildMappingXML,
  buildEntityMappingXML,
  getEntityName,
  getTargetEntity,
  versionIsCompatibleWithES,
  validateMapping
};