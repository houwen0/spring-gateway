package org.springframework.cloud.gateway.handler.predicate;

import org.springframework.http.server.PathContainer;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.util.pattern.PathPattern;
import org.springframework.web.util.pattern.PathPatternParser;

import java.util.ArrayList;
import java.util.Optional;
import java.util.function.Predicate;

import static org.springframework.http.server.PathContainer.parsePath;

public class PathRoutePredicate implements Predicate<ServerWebExchange> {

	static final ArrayList<PathPattern> pathPatterns = new ArrayList<>();

	private static PathPatternParser pathPatternParser = new PathPatternParser();
	static {
		PathPattern pathPattern = pathPatternParser.parse("/test/*");
		pathPatterns.add(pathPattern);
	}

	@Override
	public boolean test(ServerWebExchange exchange) {

		PathContainer path = parsePath(exchange.getRequest().getURI().getRawPath());
		Optional<PathPattern> optionalPathPattern = pathPatterns.stream()
				.filter(pattern -> pattern.matches(path)).findFirst();
		if (optionalPathPattern.isPresent()) {
			return true;
		}
		return false;
	}

}
