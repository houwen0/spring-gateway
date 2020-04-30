package org.springframework.cloud.gateway.handler;

import org.springframework.cloud.gateway.handler.predicate.PathRoutePredicate;
import org.springframework.cloud.gateway.route.Route;
import org.springframework.cloud.gateway.route.RouteLocator;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.reactive.handler.AbstractHandlerMapping;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.function.Function;

import static org.springframework.cloud.gateway.support.ServerWebExchangeUtils.GATEWAY_PREDICATE_ROUTE_ATTR;
import static org.springframework.cloud.gateway.support.ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR;

/**
 * @author Spencer Gibb
 */
public class RoutePredicateHandlerMapping extends AbstractHandlerMapping {

	private final FilteringWebHandler webHandler;

	private final RouteLocator routeLocator;

	public RoutePredicateHandlerMapping(FilteringWebHandler webHandler,RouteLocator routeLocator) {
		this.webHandler = webHandler;
		this.routeLocator = routeLocator;
		setOrder(1);
	}

	@Override
	protected Mono<?> getHandlerInternal(ServerWebExchange exchange) {
		        //lookupRoute返回是满足条件的第一个路由
		return lookupRoute(exchange)
				// .log("route-predicate-handler-mapping", Level.FINER) //name this
				//此处只有一个数据，满足条件的第一个路由
				//将匹配的路由放到exchange中，供webHandler中使用
				.flatMap((Function<Route, Mono<?>>) r -> {
					exchange.getAttributes().remove(GATEWAY_PREDICATE_ROUTE_ATTR);
					exchange.getAttributes().put(GATEWAY_ROUTE_ATTR, r);
					return Mono.just(webHandler);
				}).switchIfEmpty(Mono.empty().then(Mono.fromRunnable(() -> {
					exchange.getAttributes().remove(GATEWAY_PREDICATE_ROUTE_ATTR);
				})));
	}

	@Override
	protected CorsConfiguration getCorsConfiguration(Object handler,
			ServerWebExchange exchange) {
		// TODO: support cors configuration via properties on a route see gh-229
		// see RequestMappingHandlerMapping.initCorsConfiguration()
		// also see
		// https://github.com/spring-projects/spring-framework/blob/master/spring-web/src/test/java/org/springframework/web/cors/reactive/CorsWebFilterTests.java
		return super.getCorsConfiguration(handler, exchange);
	}

	protected Mono<Route> lookupRoute(ServerWebExchange exchange) {
		return this.routeLocator.getRoutes()
				// individually filter routes so that filterWhen error delaying is not a
				// problem
				//按照顺序输出返回结果
				.concatMap(route -> Mono.just(route).filterWhen(r -> {
					// add the current route we are testing
					exchange.getAttributes().put(GATEWAY_PREDICATE_ROUTE_ATTR, r.getId());
					return r.getPredicate().apply(exchange);
				}).doOnError(e -> logger.error(
						"Error applying predicate for route: " + route.getId(),
						e)).onErrorResume(e -> Mono.empty()))
				//此处第一个匹配的路由
				.next()
				// TODO: error handling
				.map(route -> {
					if (logger.isDebugEnabled()) {
						logger.debug("Route matched: " + route.getId());
					}
					validateRoute(route, exchange);
					return route;
				});

		/*
		 * TODO: trace logging if (logger.isTraceEnabled()) {
		 * logger.trace("RouteDefinition did not match: " + routeDefinition.getId()); }
		 */
	}

	protected void validateRoute(Route route, ServerWebExchange exchange) {
	}
}
