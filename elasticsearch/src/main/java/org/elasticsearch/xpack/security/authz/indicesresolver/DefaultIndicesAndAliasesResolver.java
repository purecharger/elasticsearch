/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
package org.elasticsearch.xpack.security.authz.indicesresolver;

import org.elasticsearch.action.AliasesRequest;
import org.elasticsearch.action.IndicesRequest;
import org.elasticsearch.action.admin.indices.alias.IndicesAliasesRequest;
import org.elasticsearch.action.admin.indices.alias.get.GetAliasesRequest;
import org.elasticsearch.action.admin.indices.exists.indices.IndicesExistsRequest;
import org.elasticsearch.action.admin.indices.mapping.put.PutMappingRequest;
import org.elasticsearch.action.support.IndicesOptions;
import org.elasticsearch.cluster.metadata.AliasOrIndex;
import org.elasticsearch.cluster.metadata.IndexMetaData;
import org.elasticsearch.cluster.metadata.IndexNameExpressionResolver;
import org.elasticsearch.cluster.metadata.MetaData;
import org.elasticsearch.common.regex.Regex;
import org.elasticsearch.common.util.set.Sets;
import org.elasticsearch.index.IndexNotFoundException;
import org.elasticsearch.transport.TransportRequest;
import org.elasticsearch.xpack.security.authz.AuthorizationService;
import org.elasticsearch.xpack.security.user.User;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.SortedMap;
import java.util.stream.Collectors;

public class DefaultIndicesAndAliasesResolver implements IndicesAndAliasesResolver<TransportRequest> {

    public static final String NO_INDEX = "-*";
    private static final List<String> NO_INDICES = Collections.singletonList(NO_INDEX);

    private final AuthorizationService authzService;
    private final IndexNameExpressionResolver nameExpressionResolver;

    public DefaultIndicesAndAliasesResolver(AuthorizationService authzService, IndexNameExpressionResolver nameExpressionResolver) {
        this.authzService = authzService;
        this.nameExpressionResolver = nameExpressionResolver;
    }

    @Override
    public Class<TransportRequest> requestType() {
        return TransportRequest.class;
    }

    @Override
    public Set<String> resolve(User user, String action, TransportRequest request, MetaData metaData) {
        if (request instanceof IndicesAliasesRequest) {
            Set<String> indices = new HashSet<>();
            IndicesAliasesRequest indicesAliasesRequest = (IndicesAliasesRequest) request;
            for (IndicesRequest indicesRequest : indicesAliasesRequest.getAliasActions()) {
                indices.addAll(resolveIndicesAndAliases(user, action, indicesRequest, metaData));
            }
            return indices;
        }

        // if for some reason we are missing an action... just for safety we'll reject
        if (request instanceof IndicesRequest == false) {
            throw new IllegalStateException("Request [" + request + "] is not an Indices request, but should be.");
        }
        return resolveIndicesAndAliases(user, action, (IndicesRequest) request, metaData);
    }

    private Set<String> resolveIndicesAndAliases(User user, String action, IndicesRequest indicesRequest, MetaData metaData) {
        boolean indicesReplacedWithNoIndices = false;
        final Set<String> indices;
        if (indicesRequest instanceof PutMappingRequest
                && ((PutMappingRequest) indicesRequest).getConcreteIndex() != null) {
            /*
             * This is a special case since PutMappingRequests from dynamic mapping updates have a concrete index
             * if this index is set and it's in the list of authorized indices we are good and don't need to put
             * the list of indices in there, if we do so it will result in an invalid request and the update will fail.
             */
            indices = Collections.singleton(((PutMappingRequest) indicesRequest).getConcreteIndex().getName());
            assert indicesRequest.indices() == null || indicesRequest.indices().length == 0
                    : "indices are: " + Arrays.toString(indicesRequest.indices()); // Arrays.toString() can handle null values - all good
        } else if (indicesRequest instanceof IndicesRequest.Replaceable) {
            IndicesRequest.Replaceable replaceable = (IndicesRequest.Replaceable) indicesRequest;
            final boolean replaceWildcards = indicesRequest.indicesOptions().expandWildcardsOpen()
                    || indicesRequest.indicesOptions().expandWildcardsClosed();
            List<String> authorizedIndicesAndAliases = authzService.authorizedIndicesAndAliases(user, action);

            IndicesOptions indicesOptions = indicesRequest.indicesOptions();
            if (indicesRequest instanceof IndicesExistsRequest) {
                //indices exists api should never throw exception, make sure that ignore_unavailable and allow_no_indices are true
                //we have to mimic what TransportIndicesExistsAction#checkBlock does in es core
                indicesOptions = IndicesOptions.fromOptions(true, true,
                        indicesOptions.expandWildcardsOpen(), indicesOptions.expandWildcardsClosed());
            }

            List<String> replacedIndices = new ArrayList<>();
            // check for all and return list of authorized indices
            if (IndexNameExpressionResolver.isAllIndices(indicesList(indicesRequest.indices()))) {
                if (replaceWildcards) {
                    for (String authorizedIndex : authorizedIndicesAndAliases) {
                        if (isIndexVisible(authorizedIndex, indicesOptions, metaData)) {
                            replacedIndices.add(authorizedIndex);
                        }
                    }
                }
                // if we cannot replace wildcards the indices list stays empty. Same if there are no authorized indices.
                // we honour allow_no_indices like es core does.
            } else {
                replacedIndices = replaceWildcardsWithAuthorizedIndices(indicesRequest.indices(),
                        indicesOptions, metaData, authorizedIndicesAndAliases, replaceWildcards);
                if (indicesOptions.ignoreUnavailable()) {
                    //out of all the explicit names (expanded from wildcards and original ones that were left untouched)
                    //remove all the ones that the current user is not authorized for and ignore them
                    replacedIndices = replacedIndices.stream().filter(authorizedIndicesAndAliases::contains).collect(Collectors.toList());
                }
            }
            if (replacedIndices.isEmpty()) {
                if (indicesOptions.allowNoIndices()) {
                    //this is how we tell es core to return an empty response, we can let the request through being sure
                    //that the '-*' wildcard expression will be resolved to no indices. We can't let empty indices through
                    //as that would be resolved to _all by es core.
                    replacedIndices = NO_INDICES;
                    indicesReplacedWithNoIndices = true;
                } else {
                    throw new IndexNotFoundException(Arrays.toString(indicesRequest.indices()));
                }
            }
            replaceable.indices(replacedIndices.toArray(new String[replacedIndices.size()]));
            indices = Sets.newHashSet(replacedIndices);
        } else {
            assert !containsWildcards(indicesRequest) :
                    "There are no external requests known to support wildcards that don't support replacing their indices";
            //NOTE: shard level requests do support wildcards (as they hold the original indices options) but don't support
            // replacing their indices.
            //That is fine though because they never contain wildcards, as they get replaced as part of the authorization of their
            //corresponding parent request on the coordinating node. Hence wildcards don't need to get replaced nor exploded for
            // shard level requests.
            List<String> resolvedNames = new ArrayList<>();
            for (String name : indicesRequest.indices()) {
                resolvedNames.add(nameExpressionResolver.resolveDateMathExpression(name));
            }
            indices = Sets.newHashSet(resolvedNames);
        }

        if (indicesRequest instanceof AliasesRequest) {
            //special treatment for AliasesRequest since we need to replace wildcards among the specified aliases too.
            //AliasesRequest extends IndicesRequest.Replaceable, hence its indices have already been properly replaced.
            AliasesRequest aliasesRequest = (AliasesRequest) indicesRequest;
            if (aliasesRequest.expandAliasesWildcards()) {
                List<String> authorizedIndices = authzService.authorizedIndicesAndAliases(user, action);
                List<String> aliases = replaceWildcardsWithAuthorizedAliases(aliasesRequest.aliases(), loadAuthorizedAliases
                        (authorizedIndices, metaData));
                aliasesRequest.aliases(aliases.toArray(new String[aliases.size()]));
            }
            if (indicesReplacedWithNoIndices) {
                assert indicesRequest instanceof GetAliasesRequest : GetAliasesRequest.class.getSimpleName() + " is the only known " +
                        "request implementing " + AliasesRequest.class.getSimpleName() + " that may allow no indices. Found [" +
                        indicesRequest.getClass().getName() + "] which ended up with an empty set of indices.";
            } else {
                //if we are returning '-*' we shouldn't be adding the aliases to the list or the request will not get authorized.
                //Leave only '-*' and ignore the rest, result will anyway be empty.
                Collections.addAll(indices, aliasesRequest.aliases());
            }
        }
        return indices;
    }

    private List<String> loadAuthorizedAliases(List<String> authorizedIndices, MetaData metaData) {
        List<String> authorizedAliases = new ArrayList<>();
        SortedMap<String, AliasOrIndex> existingAliases = metaData.getAliasAndIndexLookup();
        for (String authorizedIndex : authorizedIndices) {
            AliasOrIndex aliasOrIndex = existingAliases.get(authorizedIndex);
            if (aliasOrIndex != null && aliasOrIndex.isAlias()) {
                authorizedAliases.add(authorizedIndex);
            }
        }
        return authorizedAliases;
    }

    private List<String> replaceWildcardsWithAuthorizedAliases(String[] aliases, List<String> authorizedAliases) {
        List<String> finalAliases = new ArrayList<>();

        //IndicesAliasesRequest doesn't support empty aliases (validation fails) but GetAliasesRequest does (in which case empty means _all)
        boolean matchAllAliases = aliases.length == 0;
        if (matchAllAliases) {
            finalAliases.addAll(authorizedAliases);
        }

        for (String aliasPattern : aliases) {
            if (aliasPattern.equals(MetaData.ALL)) {
                matchAllAliases = true;
                finalAliases.addAll(authorizedAliases);
            } else if (Regex.isSimpleMatchPattern(aliasPattern)) {
                for (String authorizedAlias : authorizedAliases) {
                    if (Regex.simpleMatch(aliasPattern, authorizedAlias)) {
                        finalAliases.add(authorizedAlias);
                    }
                }
            } else {
                finalAliases.add(aliasPattern);
            }
        }

        //throw exception if the wildcards expansion to authorized aliases resulted in no indices.
        // This is important as we always need to replace wildcards for security reason,
        //to make sure that the operation is executed on the aliases that we authorized it to execute on.
        //If we can't replace because we got an empty set, we can only throw exception.
        if (finalAliases.isEmpty()) {
            String indexName = matchAllAliases ? MetaData.ALL : Arrays.toString(aliases);
            throw new IndexNotFoundException(indexName);
        }
        return finalAliases;
    }

    private boolean containsWildcards(IndicesRequest indicesRequest) {
        if (IndexNameExpressionResolver.isAllIndices(indicesList(indicesRequest.indices()))) {
            return true;
        }
        for (String index : indicesRequest.indices()) {
            if (index.startsWith("+") || index.startsWith("-") || Regex.isSimpleMatchPattern(index)) {
                return true;
            }
        }
        return false;
    }

    //TODO Investigate reusing code from vanilla es to resolve index names and wildcards
    private List<String> replaceWildcardsWithAuthorizedIndices(String[] indices, IndicesOptions indicesOptions, MetaData metaData,
                                                               List<String> authorizedIndices, boolean replaceWildcards) {
        //the order matters when it comes to + and - (see MetaData#convertFromWildcards)
        List<String> finalIndices = new ArrayList<>();
        for (int i = 0; i < indices.length; i++) {
            String index = indices[i];
            String aliasOrIndex;
            boolean minus = false;
            if (index.charAt(0) == '+') {
                aliasOrIndex = index.substring(1);
            } else if (index.charAt(0) == '-') {
                if (i == 0) {
                    //mimic the MetaData#convertFromWilcards behaviour with "-index" syntax
                    //but instead of adding all the indices, add only the ones that the user is authorized for
                    for (String authorizedIndex : authorizedIndices) {
                        if (isIndexVisible(authorizedIndex, indicesOptions, metaData)) {
                            finalIndices.add(authorizedIndex);
                        }
                    }
                }
                aliasOrIndex = index.substring(1);
                minus = true;
            } else {
                aliasOrIndex = index;
            }

            if (replaceWildcards && Regex.isSimpleMatchPattern(aliasOrIndex)) {
                Set<String> resolvedIndices = new HashSet<>();
                for (String authorizedIndex : authorizedIndices) {
                    if (Regex.simpleMatch(aliasOrIndex, authorizedIndex) && isIndexVisible(authorizedIndex, indicesOptions, metaData)) {
                        resolvedIndices.add(authorizedIndex);
                    }
                }
                if (resolvedIndices.isEmpty()) {
                    //es core honours allow_no_indices for each wildcard expression, we do the same here by throwing index not found.
                    if (indicesOptions.allowNoIndices() == false) {
                        throw new IndexNotFoundException(aliasOrIndex);
                    }
                } else {
                    if (minus) {
                        finalIndices.removeAll(resolvedIndices);
                    } else {
                        finalIndices.addAll(resolvedIndices);
                    }
                }
            } else {
                // we always need to check for date math expressions
                String dateMathName = nameExpressionResolver.resolveDateMathExpression(aliasOrIndex);
                // we can use != here to compare strings since the name expression resolver returns the same instance, but add an assert
                // to ensure we catch this if it changes
                if (dateMathName != aliasOrIndex) {
                    assert dateMathName.equals(aliasOrIndex) == false;
                    if (authorizedIndices.contains(dateMathName) && isIndexVisible(dateMathName, indicesOptions, metaData, true)) {
                        if (minus) {
                            finalIndices.remove(dateMathName);
                        } else {
                            finalIndices.add(dateMathName);
                        }
                    } else {
                        if (indicesOptions.ignoreUnavailable() == false) {
                            throw new IndexNotFoundException(dateMathName);
                        }
                    }
                } else {
                    //MetaData#convertFromWildcards checks if the index exists here and throws IndexNotFoundException if not (based on
                    // ignore_unavailable). We only add/remove the index: if the index is missing or the current user is not authorized
                    // to access it either an AuthorizationException will be thrown later in AuthorizationService, or the index will be
                    // removed from the list, based on the ignore_unavailable option.
                    if (minus) {
                        finalIndices.remove(aliasOrIndex);
                    } else {
                        finalIndices.add(aliasOrIndex);
                    }
                }
            }
        }
        return finalIndices;
    }

    private static boolean isIndexVisible(String index, IndicesOptions indicesOptions, MetaData metaData) {
        return isIndexVisible(index, indicesOptions, metaData, false);
    }

    private static boolean isIndexVisible(String index, IndicesOptions indicesOptions, MetaData metaData, boolean dateMathExpression) {
        if (metaData.hasConcreteIndex(index)) {
            IndexMetaData indexMetaData = metaData.index(index);
            if (indexMetaData == null) {
                //it's an alias, ignore expandWildcardsOpen and expandWildcardsClosed.
                //complicated to support those options with aliases pointing to multiple indices...
                //TODO investigate supporting expandWildcards option for aliases too, like es core does.
                return true;
            }
            if (indexMetaData.getState() == IndexMetaData.State.CLOSE && (indicesOptions.expandWildcardsClosed() || dateMathExpression)) {
                return true;
            }
            if (indexMetaData.getState() == IndexMetaData.State.OPEN && (indicesOptions.expandWildcardsOpen() || dateMathExpression)) {
                return true;
            }
        }
        return false;
    }

    private static List<String> indicesList(String[] list) {
        return (list == null) ? null : Arrays.asList(list);
    }
}
