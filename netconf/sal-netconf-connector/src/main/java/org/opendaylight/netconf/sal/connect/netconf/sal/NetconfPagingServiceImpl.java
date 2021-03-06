/*
 * Copyright (c) 2019 UTStarcom, Inc. and others.  All rights reserved.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License v1.0 which accompanies this distribution,
 * and is available at http://www.eclipse.org/legal/epl-v10.html
 */

package org.opendaylight.netconf.sal.connect.netconf.sal;

import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import com.google.common.base.Strings;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;
import com.google.common.util.concurrent.FluentFuture;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.MoreExecutors;
import com.google.common.util.concurrent.SettableFuture;
import org.opendaylight.mdsal.binding.dom.codec.api.BindingNormalizedNodeSerializer;
import org.opendaylight.mdsal.dom.api.DOMMountPoint;
import org.opendaylight.mdsal.dom.api.DOMMountPointService;
import org.opendaylight.mdsal.dom.api.DOMRpcResult;
import org.opendaylight.mdsal.dom.api.DOMRpcService;
import org.opendaylight.netconf.api.xml.XmlUtil;
import org.opendaylight.netconf.sal.connect.netconf.util.NetconfBaseOps;
import org.opendaylight.netconf.sal.connect.netconf.util.NetconfMessageTransformUtil;
import org.opendaylight.yang.gen.v1.urn.tbd.params.xml.ns.yang.network.topology.rev131021.NetworkTopology;
import org.opendaylight.yang.gen.v1.urn.tbd.params.xml.ns.yang.network.topology.rev131021.network.topology.Topology;
import org.opendaylight.yang.gen.v1.urn.tbd.params.xml.ns.yang.network.topology.rev131021.network.topology.topology.Node;
import org.opendaylight.yangtools.yang.binding.DataObject;
import org.opendaylight.yangtools.yang.common.QName;
import org.opendaylight.yangtools.yang.data.api.YangInstanceIdentifier;
import org.opendaylight.yangtools.yang.data.api.schema.*;
import org.opendaylight.yangtools.yang.data.impl.schema.Builders;
import org.opendaylight.yangtools.yang.data.impl.schema.builder.api.NormalizedNodeAttrBuilder;
import org.opendaylight.yangtools.yang.model.api.Module;
import org.opendaylight.yangtools.yang.model.api.SchemaPath;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

import javax.annotation.Nullable;
import javax.xml.transform.dom.DOMSource;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.opendaylight.netconf.sal.connect.netconf.util.NetconfMessageTransformUtil.*;

/**
 * Netconf分页功能
 * <p>
 *
 * @Author Huafei Zhang
 */
public class NetconfPagingServiceImpl implements NetconfPagingService {

    private DOMMountPointService domMountService;
    final private BindingNormalizedNodeSerializer codec;
    private ExtCmdService extCmdService;

    private static final String CONDITION_REGEX = "(\\w+)(>=|<=|<|=|>){1}(.*)";

    private static final String XPATH = "xpath";
    private static final QName NETCONF_SELECT_QNAME = QName.create(NETCONF_QNAME, "select").intern();
    private static final String NAMESPACE_PREFIX = "t";

    public NetconfPagingServiceImpl(BindingNormalizedNodeSerializer codec, DOMMountPointService domMountService, ExtCmdService extCmdService) {
        this.codec = codec;
        this.domMountService = domMountService;
        this.extCmdService = extCmdService;
    }

    public ListenableFuture<Integer> queryCount(String nodeId, String moduleName, TableType type, @Nullable String... expressions) {
        Preconditions.checkNotNull(type, "Table type should not be null");

        YangInstanceIdentifier nodeII = ExtCmdService.toYangNodeII(nodeId);
        Optional<DOMMountPoint> mountPointOpt = domMountService.getMountPoint(nodeII);
        if (!mountPointOpt.isPresent()) {
            SettableFuture<Integer> future = SettableFuture.create();
            future.setException(new IllegalStateException("Specified mount point " + nodeId + " not exist"));
            return future;
        }

        Set<Module> modules = mountPointOpt.get().getSchemaContext().findModules(moduleName);
        if (modules == null || modules.isEmpty()) {
            SettableFuture<Integer> future = SettableFuture.create();
            future.setException(new IllegalStateException("Unable to find module " + moduleName));
            return future;
        }

        String paraValue = createCountPara(moduleName, type, expressions);
        FluentFuture<String> resultFuture = extCmdService.extCmdTo(nodeId, 1, "queryCnt", "execute", 10, 1, paraValue);

        return resultFuture.transform(result -> {
            if (result == null) {
                return 0;
            }
            return Integer.parseInt(result);
        }, MoreExecutors.directExecutor());
    }

    private String createCountPara(String moduleName, TableType type, @Nullable String[] expressions) {

        String paraValue = String.format("{\"DsName\",{String,\"%s\"}},{\"TblName\",{String,\"%s\"}}", type.toString(), moduleName);
        if (expressions != null && expressions.length > 0) {
            //{"filter",{String,"Almid>100 and Almid<500"}
            StringBuilder targetExp = new StringBuilder();
            targetExp.append(expressions[0]);
            if (expressions.length > 1) {
                for (int i = 1; i < expressions.length; i++) {
                    targetExp.append(" and ");
                    targetExp.append(expressions[i]);
                }
            }
            paraValue = paraValue + String.format("{\"filter\",{String,\"%s\"}", targetExp.toString());
        }

        return "{" + paraValue + "}";
    }

    @Override
    public <T extends DataObject> FluentFuture<Optional<T>> query(String nodeId, final String moduleName,
                                                                  @Nullable Integer start, @Nullable Integer num, @Nullable String... expressions) {
        YangInstanceIdentifier yangII = NetconfPagingService.toTableYangII(moduleName);
        return find(nodeId, moduleName, start, num, expressions).transform(resutOpt -> {
            if (!resutOpt.isPresent()) {
                return Optional.<T>absent();
            }
            return Optional.of((T) codec.fromNormalizedNode(yangII, resutOpt.get()).getValue());
        }, MoreExecutors.directExecutor());
    }

    @Override
    public <T extends DataObject> FluentFuture<Optional<NormalizedNode<?, ?>>> find(String nodeId, String moduleName,
                                                                                    @Nullable Integer start, @Nullable Integer num, @Nullable String... expressions) {
        checkArgument(start, num, expressions);

        YangInstanceIdentifier yangII = NetconfPagingService.toTableYangII(moduleName);
        YangInstanceIdentifier nodeII = ExtCmdService.toYangNodeII(nodeId);
        Optional<DOMMountPoint> mountPointOpt = domMountService.getMountPoint(nodeII);
        if (!mountPointOpt.isPresent()) {
            SettableFuture<Optional<NormalizedNode<?, ?>>> future = SettableFuture.create();
            future.setException(new IllegalStateException("Specified mount point " + nodeId + " not exist"));
            return FluentFuture.from(future);
        }

        DOMRpcService rpcService = mountPointOpt.get().getService(DOMRpcService.class).get();
        SchemaPath type = SchemaPath.create(true, NETCONF_GET_CONFIG_QNAME);
        final NormalizedNodeAttrBuilder<YangInstanceIdentifier.NodeIdentifier, DOMSource, AnyXmlNode> anyXmlBuilder =
                toFitlerStructure(moduleName, start, num, expressions);
        DataContainerChild<?, ?> invokeInput = NetconfMessageTransformUtil.wrap(NETCONF_GET_CONFIG_QNAME,
                NetconfBaseOps.getSourceNode(NETCONF_RUNNING_QNAME), anyXmlBuilder.build());

        FluentFuture<DOMRpcResult> resultFuture = rpcService.invokeRpc(type, invokeInput);
        return resultFuture.transform(domRpcResult -> {
            Preconditions.checkArgument(domRpcResult.getErrors().isEmpty(), "Unable to read data: %s, errors: %s",
                    NetconfPagingService.topContainerName(moduleName), domRpcResult.getErrors());
            final DataContainerChild<? extends YangInstanceIdentifier.PathArgument, ?> dataNode =
                    ((ContainerNode) domRpcResult.getResult())
                            .getChild(NetconfMessageTransformUtil.toId(NETCONF_DATA_QNAME)).get();

            java.util.Optional<NormalizedNode<?, ?>> normalizedNodeOptional =
                    NormalizedNodes.findNode(dataNode, yangII.getPathArguments());

            return Optional.fromJavaUtil(normalizedNodeOptional);

        }, MoreExecutors.directExecutor());
    }

    private void checkArgument(@Nullable Integer start, @Nullable Integer num, @Nullable String[] expressions) {
        Preconditions.checkArgument(((start != null) && (num != null)) || (expressions != null), "at least one condition is specified");
        if ((start != null) && (num != null)) {
            Preconditions.checkArgument(start >= 0 && num > 0, "start and num should be non-negative");
        }

        if (expressions != null) {
            for (String exp : expressions) {
                Preconditions.checkArgument(exp.matches(CONDITION_REGEX), "%s: format not correct", exp);
            }
        }
    }


    private <T extends DataObject> NormalizedNodeAttrBuilder<YangInstanceIdentifier.NodeIdentifier, DOMSource, AnyXmlNode> toFitlerStructure(
            String moduleName, Integer start, Integer num, String... expressions) {
        final NormalizedNodeAttrBuilder<YangInstanceIdentifier.NodeIdentifier, DOMSource, AnyXmlNode> anyXmlBuilder =
                Builders.anyXmlBuilder().withNodeIdentifier(new YangInstanceIdentifier.NodeIdentifier(
                        QName.create(NETCONF_QNAME, NETCONF_FILTER_QNAME.getLocalName()).intern()));
        Map<QName, String> attributes = Maps.newHashMap();
        anyXmlBuilder.withAttributes(attributes);

        Document doc = XmlUtil.newDocument();
        final Element element =
                doc.createElementNS(NETCONF_FILTER_QNAME.getNamespace().toString(), NETCONF_FILTER_QNAME.getLocalName());
        element.setAttribute(NETCONF_TYPE_QNAME.getLocalName(), XPATH);
        element.setAttribute(NETCONF_SELECT_QNAME.getLocalName(), toXpathExp(NetconfPagingService.topContainerName(moduleName), start, num, expressions));

        String namespace = NetconfPagingService.namespace(moduleName);
        element.setAttribute(XmlUtil.XMLNS_ATTRIBUTE_KEY + ":" + NAMESPACE_PREFIX, namespace);
        anyXmlBuilder.withValue(new DOMSource(element));
        return anyXmlBuilder;
    }

    private String toXpathExp(String topContainerName, Integer start, Integer num, String... expressions) {

        String prefixSlash = "/" + NAMESPACE_PREFIX + ":";
        String listName = topContainerName.substring(0, topContainerName.length() - 1);
        String startPath = prefixSlash + topContainerName + prefixSlash + listName;

        StringBuilder stringBuilder = new StringBuilder(startPath);
        if (expressions != null) {
            for (String expression : expressions) {
                String fexp = String.format("[%s]", quoteExp(expression));
                stringBuilder.append(fexp);
            }
        }

        String limit = toLimit(start, num);
        if (limit != null) {
            stringBuilder.append(limit);
        }
        return stringBuilder.toString();
    }

    private String quoteExp(String expression) {
        Pattern pattern = Pattern.compile(CONDITION_REGEX);
        Matcher matcher = pattern.matcher(expression);
        if (matcher.matches()) {
            String key = matcher.group(1);
            String operator = matcher.group(2);
            String value = matcher.group(3);
            return key + operator + "'" + value + "'";
        }
        throw new IllegalArgumentException();
    }

    private String toLimit(Integer start, Integer num) {
        if (start == null || num == null) {
            return null;
        }
        String limit = String.format("[LIMIT()-%s+%s]", start, num);
        return limit;
    }
}
