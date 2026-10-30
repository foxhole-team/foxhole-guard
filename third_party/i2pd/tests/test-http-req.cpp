#include <cassert>
#include "HTTP.h"

using namespace i2p::http;

int main() {
  HTTPReq *req;
  int ret = 0, len = 0;
  const char *buf;

  /* test: parsing request with body */
  buf =
    "GET / HTTP/1.0\r\n"
    "User-Agent: curl/7.26.0\r\n"
    "Host: inr.i2p\r\n"
    "Accept: */*\r\n"
    "\r\n"
    "test";
  len = strlen(buf);
  req = new HTTPReq;
  assert((ret = req->parse(buf, len)) == len - 4);
  assert(req->version == "HTTP/1.0");
  assert(req->method == "GET");
  assert(req->uri == "/");
  assert(req->GetNumHeaders () == 3);
  assert(req->GetNumHeaders("Host") == 1);
  assert(req->GetNumHeaders("Accept") == 1);
  assert(req->GetNumHeaders("User-Agent") == 1);
  assert(req->GetHeader("Host")  == "inr.i2p");
  assert(req->GetHeader("Accept") == "*/*");
  assert(req->GetHeader("User-Agent")  == "curl/7.26.0");
  delete req;

  /* test: parsing request without body */
  buf =
    "GET / HTTP/1.0\r\n"
    "\r\n";
  len = strlen(buf);
  req = new HTTPReq;
  assert((ret = req->parse(buf, len)) == len);
  assert(req->version == "HTTP/1.0");
  assert(req->method == "GET");
  assert(req->uri == "/");
  assert(req->GetNumHeaders () == 0);
  delete req;

  /* test: parsing request without body */
  buf =
    "GET / HTTP/1.1\r\n"
    "\r\n";
  len = strlen(buf);
  req = new HTTPReq;
  assert((ret = req->parse(buf, len)) > 0);
  delete req;

  /* test: parsing incomplete request */
  buf =
    "GET / HTTP/1.0\r\n"
    "";
  len = strlen(buf);
  req = new HTTPReq;
  assert((ret = req->parse(buf, len)) == 0); /* request not completed */
  delete req;

  /* test: parsing slightly malformed request */
  buf =
    "GET http://inr.i2p HTTP/1.1\r\n"
    "Host:  stats.i2p\r\n"
    "Accept-Encoding: \r\n"
    "Accept: */*\r\n"
    "\r\n";
  len = strlen(buf);
  req = new HTTPReq;
  assert((ret = req->parse(buf, len)) == len); /* no host header */
  assert(req->method == "GET");
  assert(req->uri == "http://inr.i2p");
  assert(req->GetNumHeaders () == 3);
  assert(req->GetNumHeaders("Host") == 1);
  assert(req->GetNumHeaders("Accept") == 1);
  assert(req->GetNumHeaders("Accept-Encoding") == 1);
  assert(req->GetHeader("Host") == "stats.i2p");
  assert(req->GetHeader("Accept") == "*/*");
  assert(req->GetHeader("Accept-Encoding") == "");
  delete req;

  /* test: case-insensitive header matching (RFC 7230) */
  buf =
    "GET / HTTP/1.1\r\n"
    "HOST: example.i2p\r\n"
    "content-type: text/html\r\n"
    "Content-Length: 100\r\n"
    "X-Custom-Header: value\r\n"
    "\r\n";
  len = strlen(buf);
  req = new HTTPReq;
  assert((ret = req->parse(buf, len)) == len);
  /* GetHeader should be case-insensitive */
  assert(req->GetHeader("Host") == "example.i2p");
  assert(req->GetHeader("host") == "example.i2p");
  assert(req->GetHeader("HOST") == "example.i2p");
  assert(req->GetHeader("Content-Type") == "text/html");
  assert(req->GetHeader("content-type") == "text/html");
  assert(req->GetHeader("CONTENT-TYPE") == "text/html");
  assert(req->GetHeader("Content-Length") == "100");
  assert(req->GetHeader("content-length") == "100");
  assert(req->GetHeader("x-custom-header") == "value");
  assert(req->GetHeader("X-CUSTOM-HEADER") == "value");
  /* GetNumHeaders should be case-insensitive */
  assert(req->GetNumHeaders("Host") == 1);
  assert(req->GetNumHeaders("host") == 1);
  assert(req->GetNumHeaders("HOST") == 1);
  assert(req->GetNumHeaders("content-type") == 1);
  delete req;

  /* test: UpdateHeader case-insensitivity */
  req = new HTTPReq;
  req->AddHeader("Content-Type", "text/plain");
  assert(req->GetHeader("Content-Type") == "text/plain");
  req->UpdateHeader("content-type", "application/json");
  assert(req->GetHeader("Content-Type") == "application/json");
  assert(req->GetHeader("CONTENT-TYPE") == "application/json");
  req->UpdateHeader("CONTENT-TYPE", "text/html");
  assert(req->GetHeader("content-type") == "text/html");
  delete req;

  /* test: RemoveHeader case-insensitivity */
  req = new HTTPReq;
  req->AddHeader("X-Forwarded-For", "1.2.3.4");
  req->AddHeader("X-Forwarded-Host", "proxy.i2p");
  req->AddHeader("Accept", "*/*");
  assert(req->GetNumHeaders() == 3);
  /* remove headers starting with "x-forwarded" (case-insensitive), exempt "X-Forwarded-Host" */
  req->RemoveHeader("X-Forwarded", "x-forwarded-host");
  assert(req->GetNumHeaders() == 2);
  assert(req->GetHeader("X-Forwarded-Host") == "proxy.i2p");
  assert(req->GetHeader("X-Forwarded-For") == "");
  delete req;

  return 0;
}

/* vim: expandtab:ts=2 */
