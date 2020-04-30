/*
 * Copyright 2013-2019 the original author or authors.
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

package org.springframework.cloud.gateway.route;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.cloud.gateway.config.GatewayProperties;
//import org.springframework.cloud.gateway.event.FilterArgsEvent;
//import org.springframework.cloud.gateway.event.PredicateArgsEvent;
import org.springframework.cloud.gateway.filter.FilterDefinition;
import org.springframework.cloud.gateway.filter.GatewayFilter;
import org.springframework.cloud.gateway.filter.OrderedGatewayFilter;
import org.springframework.cloud.gateway.filter.factory.GatewayFilterFactory;
import org.springframework.cloud.gateway.handler.AsyncPredicate;
import org.springframework.cloud.gateway.handler.predicate.PredicateDefinition;
import org.springframework.cloud.gateway.handler.predicate.RoutePredicateFactory;
import org.springframework.cloud.gateway.support.ConfigurationService;
import org.springframework.cloud.gateway.support.HasRouteId;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.AnnotationAwareOrderComparator;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Flux;

import java.util.*;

/**
 * 将原始的RouteDefinition对象进行，进行加工转换为Route对象信息
 * {@link RouteLocator} that loads routes from a {@link RouteDefinitionLocator}.
 *
 * @author Spencer Gibb
 */
public class RouteDefinitionRouteLocator implements RouteLocator {

	/**
	 * Default filters name.
	 */
	public static final String DEFAULT_FILTERS = "defaultFilters";

	protected final Log logger = LogFactory.getLog(getClass());

	private final RouteDefinitionLocator routeDefinitionLocator;

	private final ConfigurationService configurationService;

	private final Map<String, RoutePredicateFactory> predicates = new LinkedHashMap<>();

	private final Map<String, GatewayFilterFactory> gatewayFilterFactories = new HashMap<>();

	private final GatewayProperties gatewayProperties;

	public RouteDefinitionRouteLocator(RouteDefinitionLocator routeDefinitionLocator,
                                       List<RoutePredicateFactory> predicates,
                                       List<GatewayFilterFactory> gatewayFilterFactories,
                                       GatewayProperties gatewayProperties,
                                       ConfigurationService configurationService) {
		this.routeDefinitionLocator = routeDefinitionLocator;
		this.configurationService = configurationService;
		initFactories(predicates);
		gatewayFilterFactories.forEach(
				factory -> this.gatewayFilterFactories.put(factory.name(), factory));
		this.gatewayProperties = gatewayProperties;
	}

	private void initFactories(List<RoutePredicateFactory> predicates) {
		predicates.forEach(factory -> {
			String key = factory.name();
			if (this.predicates.containsKey(key)) {
				this.logger.warn("A RoutePredicateFactory named " + key
						+ " already exists, class: " + this.predicates.get(key)
						+ ". It will be overwritten.");
			}
			this.predicates.put(key, factory);
			if (logger.isInfoEnabled()) {
				logger.info("Loaded RoutePredicateFactory [" + key + "]");
			}
		});
	}

	@Override
	public Flux<Route> getRoutes() {
		Flux<Route> routes = this.routeDefinitionLocator.getRouteDefinitions()
				.map(this::convertToRoute);

		if (!gatewayProperties.isFailOnRouteDefinitionError()) {
			// instead of letting error bubble up, continue
			routes = routes.onErrorContinue((error, obj) -> {
				if (logger.isWarnEnabled()) {
					logger.warn("RouteDefinition id " + ((RouteDefinition) obj).getId()
							+ " will be ignored. Definition has invalid configs, "
							+ error.getMessage());
				}
			});
		}

		return routes.map(route -> {
			if (logger.isDebugEnabled()) {
				logger.info("RouteDefinition matched: " + route.getId());
			}
			return route;
		});
	}

	/**
	 * 将yaml文件中的路由定义转换为Route对象
	 * @param routeDefinition  yaml文件中加载的路由定义
	 * @return
	 */
	private Route convertToRoute(RouteDefinition routeDefinition) {
		//此处断言通过二叉树进行组织
		AsyncPredicate<ServerWebExchange> predicate = combinePredicates(routeDefinition);
		//将路由器中配置的默认过滤器和GateFilter进行组装并排序
		List<GatewayFilter> gatewayFilters = getFilters(routeDefinition);

		//建造者模式，最终返回Route对象
		return Route.async(routeDefinition).asyncPredicate(predicate)
				.replaceFilters(gatewayFilters).build();
	}

	//将配置中的过滤器进行实例化，包装为带order的GatewayFilter
	@SuppressWarnings("unchecked")
	List<GatewayFilter> loadGatewayFilters(String id,
                                           List<FilterDefinition> filterDefinitions) {
		ArrayList<GatewayFilter> ordered = new ArrayList<>(filterDefinitions.size());
		for (int i = 0; i < filterDefinitions.size(); i++) {
			FilterDefinition definition = filterDefinitions.get(i);
			GatewayFilterFactory factory = this.gatewayFilterFactories
					.get(definition.getName());
			if (factory == null) {
				throw new IllegalArgumentException(
						"Unable to find GatewayFilterFactory with name "
								+ definition.getName());
			}
			if (logger.isDebugEnabled()) {
				logger.debug("RouteDefinition " + id + " applying filter "
						+ definition.getArgs() + " to " + definition.getName());
			}

			// @formatter:off
			Object configuration = this.configurationService.with(factory)
					.name(definition.getName())
					.properties(definition.getArgs())
//					.eventFunction((bound, properties) -> new FilterArgsEvent(
//							// TODO: why explicit cast needed or java compile fails
//							RouteDefinitionRouteLocator.this, id, (Map<String, Object>) properties))
					.bind();
			// @formatter:on

			//配置中存在routeId时，需要将routeId赋值
			// some filters require routeId
			// TODO: is there a better place to apply this?
			if (configuration instanceof HasRouteId) {
				HasRouteId hasRouteId = (HasRouteId) configuration;
				hasRouteId.setRouteId(id);
			}

			GatewayFilter gatewayFilter = factory.apply(configuration);
			if (gatewayFilter instanceof Ordered) {
				ordered.add(gatewayFilter);
			}
			else {
				ordered.add(new OrderedGatewayFilter(gatewayFilter, i + 1));
			}
		}

		return ordered;
	}

	private List<GatewayFilter> getFilters(RouteDefinition routeDefinition) {
		List<GatewayFilter> filters = new ArrayList<>();

		//添加路由器中默认的过滤器
		// TODO: support option to apply defaults after route specific filters?
		if (!this.gatewayProperties.getDefaultFilters().isEmpty()) {
			filters.addAll(loadGatewayFilters(DEFAULT_FILTERS,
					this.gatewayProperties.getDefaultFilters()));
		}
        //添加路由器中配置的过滤器
		if (!routeDefinition.getFilters().isEmpty()) {
			filters.addAll(loadGatewayFilters(routeDefinition.getId(),
					routeDefinition.getFilters()));
		}

		AnnotationAwareOrderComparator.sort(filters);
		return filters;
	}

	/**
	 * 组合断言,将断言组合为二叉树的结构，最先加载的放在叶子节点，逐步向上填充
	 * 多个断言组合通过“and”的方式组合
	 * 其中每个节点中包含三层
	 * @param routeDefinition
	 * @return
	 */
	private AsyncPredicate<ServerWebExchange> combinePredicates(
			RouteDefinition routeDefinition) {
		List<PredicateDefinition> predicates = routeDefinition.getPredicates();
		AsyncPredicate<ServerWebExchange> predicate = lookup(routeDefinition,
				predicates.get(0));

		for (PredicateDefinition andPredicate : predicates.subList(1,
				predicates.size())) {
			AsyncPredicate<ServerWebExchange> found = lookup(routeDefinition,
					andPredicate);
			predicate = predicate.and(found);
		}

		return predicate;
	}

	/**
	 * 获取AsyncPredicate对象，返回对象为
	 * 第一层：  节点(DefaultAsyncPredicate)
	 * 第二层：   delegate节点（Predicate<T>）
	 * 第三层：   right节点  具体的断言节点（由断言工厂生成）
	 * @param route
	 * @param predicate
	 * @return
	 */
	@SuppressWarnings("unchecked")
	private AsyncPredicate<ServerWebExchange> lookup(RouteDefinition route,
                                                     PredicateDefinition predicate) {
		RoutePredicateFactory<Object> factory = this.predicates.get(predicate.getName());
		if (factory == null) {
			throw new IllegalArgumentException(
					"Unable to find RoutePredicateFactory with name "
							+ predicate.getName());
		}
		if (logger.isDebugEnabled()) {
			logger.debug("RouteDefinition " + route.getId() + " applying "
					+ predicate.getArgs() + " to " + predicate.getName());
		}

		// @formatter:off
		Object config = this.configurationService.with(factory)
				.name(predicate.getName())
				.properties(predicate.getArgs())
//				.eventFunction((bound, properties) -> new PredicateArgsEvent(
//						RouteDefinitionRouteLocator.this, route.getId(), properties))
				.bind();
		// @formatter:on

		return factory.applyAsync(config);
	}

}
