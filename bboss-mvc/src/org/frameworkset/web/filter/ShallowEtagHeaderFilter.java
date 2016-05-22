package org.frameworkset.web.filter;

import java.io.IOException;
import java.io.InputStream;
import java.io.PrintWriter;

import javax.servlet.FilterChain;
import javax.servlet.ServletException;
import javax.servlet.ServletOutputStream;
import javax.servlet.ServletRequest;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.frameworkset.util.Assert;
import org.frameworkset.util.ClassUtils;
import org.frameworkset.util.DigestUtils;
import org.frameworkset.util.annotations.HttpMethod;
import org.frameworkset.web.util.ContentCachingResponseWrapper;
import org.frameworkset.web.util.WebUtils;

/**
 * {@link javax.servlet.Filter} that generates an {@code ETag} value based on the
 * content on the response. This ETag is compared to the {@code If-None-Match}
 * header of the request. If these headers are equal, the response content is
 * not sent, but rather a {@code 304 "Not Modified"} status instead.
 *
 * <p>Since the ETag is based on the response content, the response
 * (e.g. a {@link org.springframework.web.servlet.View}) is still rendered.
 * As such, this filter only saves bandwidth, not server performance.
 *
 * @author Arjen Poutsma
 * @author Rossen Stoyanchev
 * @author Juergen Hoeller
 * @since 3.0
 */
public class ShallowEtagHeaderFilter extends OncePerRequestFilter {

	private static final String HEADER_ETAG = "ETag";

	private static final String HEADER_IF_NONE_MATCH = "If-None-Match";

	private static final String HEADER_CACHE_CONTROL = "Cache-Control";

	private static final String DIRECTIVE_NO_STORE = "no-store";

	private static final String STREAMING_ATTRIBUTE = ShallowEtagHeaderFilter.class.getName() + ".STREAMING";


	/** Checking for Servlet 3.0+ HttpServletResponse.getHeader(String) */
	private static final boolean servlet3Present =
			ClassUtils.hasMethod(HttpServletResponse.class, "getHeader", String.class);


	/**
	 * The default value is "false" so that the filter may delay the generation of
	 * an ETag until the last asynchronously dispatched thread.
	 */
	@Override
	protected boolean shouldNotFilterAsyncDispatch() {
		return false;
	}

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
			throws ServletException, IOException {

		HttpServletResponse responseToUse = response;
		if (!isAsyncDispatch(request) && !(response instanceof ContentCachingResponseWrapper)) {
			responseToUse = new HttpStreamingAwareContentCachingResponseWrapper(response, request);
		}

		filterChain.doFilter(request, responseToUse);

		if (!isAsyncStarted(request) && !isContentCachingDisabled(request)) {
			updateResponse(request, responseToUse);
		}
	}

	private void updateResponse(HttpServletRequest request, HttpServletResponse response) throws IOException {
		ContentCachingResponseWrapper responseWrapper =
				WebUtils.getNativeResponse(response, ContentCachingResponseWrapper.class);
		Assert.notNull(responseWrapper, "ContentCachingResponseWrapper not found");
		HttpServletResponse rawResponse = (HttpServletResponse) responseWrapper.getResponse();
		int statusCode = responseWrapper.getStatusCode();

		if (rawResponse.isCommitted()) {
			responseWrapper.copyBodyToResponse();
		}
		else if (isEligibleForEtag(request, responseWrapper, statusCode, responseWrapper.getContentInputStream())) {
			String responseETag = generateETagHeaderValue(responseWrapper.getContentInputStream());
			rawResponse.setHeader(HEADER_ETAG, responseETag);
			String requestETag = request.getHeader(HEADER_IF_NONE_MATCH);
			if (responseETag.equals(requestETag)) {
				if (logger.isTraceEnabled()) {
					logger.trace("ETag [" + responseETag + "] equal to If-None-Match, sending 304");
				}
				rawResponse.setStatus(HttpServletResponse.SC_NOT_MODIFIED);
			}
			else {
				if (logger.isTraceEnabled()) {
					logger.trace("ETag [" + responseETag + "] not equal to If-None-Match [" + requestETag +
							"], sending normal response");
				}
				responseWrapper.copyBodyToResponse();
			}
		}
		else {
			if (logger.isTraceEnabled()) {
				logger.trace("Response with status code [" + statusCode + "] not eligible for ETag");
			}
			responseWrapper.copyBodyToResponse();
		}
	}

	/**
	 * Indicates whether the given request and response are eligible for ETag generation.
	 * <p>The default implementation returns {@code true} if all conditions match:
	 * <ul>
	 * <li>response status codes in the {@code 2xx} series</li>
	 * <li>request method is a GET</li>
	 * <li>response Cache-Control header is not set or does not contain a "no-store" directive</li>
	 * </ul>
	 * @param request the HTTP request
	 * @param response the HTTP response
	 * @param responseStatusCode the HTTP response status code
	 * @param inputStream the response body
	 * @return {@code true} if eligible for ETag generation; {@code false} otherwise
	 */
	protected boolean isEligibleForEtag(HttpServletRequest request, HttpServletResponse response,
			int responseStatusCode, InputStream inputStream) {

		if (responseStatusCode >= 200 && responseStatusCode < 300 && HttpMethod.GET.matches(request.getMethod())) {
			String cacheControl = null;
			if (servlet3Present) {
				cacheControl = response.getHeader(HEADER_CACHE_CONTROL);
			}
			if (cacheControl == null || !cacheControl.contains(DIRECTIVE_NO_STORE)) {
				return true;
			}
		}
		return false;
	}

	/**
	 * Generate the ETag header value from the given response body byte array.
	 * <p>The default implementation generates an MD5 hash.
	 * @param inputStream the response body as an InputStream
	 * @return the ETag header value
	 * @see org.springframework.util.DigestUtils
	 */
	protected String generateETagHeaderValue(InputStream inputStream) throws IOException {
		StringBuilder builder = new StringBuilder("\"0");
		DigestUtils.appendMd5DigestAsHex(inputStream, builder);
		builder.append('"');
		return builder.toString();
	}


	/**
	 * This method can be used to disable the content caching response wrapper
	 * of the ShallowEtagHeaderFilter. This can be done before the start of HTTP
	 * streaming for example where the response will be written to asynchronously
	 * and not in the context of a Servlet container thread.
	 * @since 4.2
	 */
	public static void disableContentCaching(ServletRequest request) {
		Assert.notNull(request, "ServletRequest must not be null");
		request.setAttribute(STREAMING_ATTRIBUTE, true);
	}

	private static boolean isContentCachingDisabled(HttpServletRequest request) {
		return (request.getAttribute(STREAMING_ATTRIBUTE) != null);
	}


	private static class HttpStreamingAwareContentCachingResponseWrapper extends ContentCachingResponseWrapper {

		private final HttpServletRequest request;

		public HttpStreamingAwareContentCachingResponseWrapper(HttpServletResponse response, HttpServletRequest request) {
			super(response);
			this.request = request;
		}

		@Override
		public ServletOutputStream getOutputStream() throws IOException {
			return (useRawResponse() ? getResponse().getOutputStream() : super.getOutputStream());
		}

		@Override
		public PrintWriter getWriter() throws IOException {
			return (useRawResponse() ? getResponse().getWriter() : super.getWriter());
		}

		private boolean useRawResponse() {
			return isContentCachingDisabled(this.request);
		}
	}
}
