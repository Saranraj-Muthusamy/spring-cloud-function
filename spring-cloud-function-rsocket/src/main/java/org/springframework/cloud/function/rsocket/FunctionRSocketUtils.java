/*
 * Copyright 2020-2020 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.springframework.cloud.function.rsocket;

import java.lang.reflect.Type;
import java.net.URI;
import java.util.regex.Pattern;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import org.springframework.cloud.function.context.FunctionCatalog;
import org.springframework.cloud.function.context.FunctionProperties;
import org.springframework.cloud.function.context.FunctionRegistration;
import org.springframework.cloud.function.context.FunctionRegistry;
import org.springframework.cloud.function.context.catalog.FunctionTypeUtils;
import org.springframework.cloud.function.context.catalog.SimpleFunctionRegistry.FunctionInvocationWrapper;
import org.springframework.context.ApplicationContext;
import org.springframework.messaging.rsocket.RSocketRequester;
import org.springframework.messaging.rsocket.RSocketRequester.Builder;
import org.springframework.util.Assert;
import org.springframework.util.StringUtils;

/**
 *
 * @author Oleg Zhurakousky
 *
 * @since 3.1
 *
 */
final class FunctionRSocketUtils {

	private static final Log LOGGER = LogFactory.getLog(FunctionRSocketUtils.class);

	private static final Pattern WS_URI_PATTERN = Pattern.compile("^(https?|wss?)://.+");

	private FunctionRSocketUtils() {

	}

	static FunctionInvocationWrapper registerFunctionForDestination(String functionDefinition, FunctionCatalog functionCatalog,
			ApplicationContext applicationContext) {

		registerRSocketForwardingFunctionIfNecessary(functionDefinition, functionCatalog, applicationContext);
		FunctionProperties functionProperties = applicationContext.getBean(FunctionProperties.class);
		String acceptContentType = functionProperties.getAccept();
		if (!StringUtils.hasText(acceptContentType)) {
			FunctionInvocationWrapper function = functionCatalog.lookup(functionDefinition);
			Type functionType = function.getFunctionType();
			Type outputType = FunctionTypeUtils.getOutputType(functionType, 0);
			if (outputType instanceof Class && String.class.isAssignableFrom((Class<?>) outputType)) {
				acceptContentType = "text/plain";
			}
			else {
				acceptContentType = "application/json";
			}
		}

		FunctionInvocationWrapper function = functionCatalog.lookup(functionDefinition, acceptContentType);
		return function;
	}

	static void registerRSocketForwardingFunctionIfNecessary(String definition, FunctionCatalog functionCatalog,
			ApplicationContext applicationContext) {
		String[] names = StringUtils.delimitedListToStringArray(definition.replaceAll(",", "|").trim(), "|");
		for (String name : names) {
			if (!applicationContext.containsBean(name)) { // this means RSocket
				if (LOGGER.isDebugEnabled()) {
					LOGGER.debug("Registering RSocket forwarder for '" + name + "' function.");
				}
				String[] functionToRSocketDefinition = StringUtils.delimitedListToStringArray(name, ">");
				Assert.isTrue(functionToRSocketDefinition.length == 2, "Must only contain one output redirect");
				FunctionInvocationWrapper function = functionCatalog.lookup(functionToRSocketDefinition[0], "application/json");

				String[] hostPort = StringUtils.delimitedListToStringArray(functionToRSocketDefinition[1], ":");

				String forwardingUrl = functionToRSocketDefinition[1];
				Builder rsocketRequesterBuilder = applicationContext.getBean(Builder.class);

				RSocketRequester rsocketRequester = (WS_URI_PATTERN.matcher(forwardingUrl).matches())
						? rsocketRequesterBuilder.websocket(URI.create(forwardingUrl))
						: rsocketRequesterBuilder.tcp(hostPort[0], Integer.parseInt(hostPort[1]));

				RSocketForwardingFunction rsocketFunction =
					new RSocketForwardingFunction(function, rsocketRequester, null);
				FunctionRegistration<RSocketForwardingFunction> functionRegistration =
					new FunctionRegistration<>(rsocketFunction, name);
				functionRegistration.type(
					FunctionTypeUtils.discoverFunctionTypeFromClass(RSocketForwardingFunction.class));
				((FunctionRegistry) functionCatalog).register(functionRegistration);
			}
		}
	}
}
