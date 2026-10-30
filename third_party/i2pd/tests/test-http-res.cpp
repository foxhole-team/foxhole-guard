#include <cassert>
#include "HTTP.h"

using namespace i2p::http;

int main() {
  HTTPRes *res;
  int ret = 0, len = 0;
  const char *buf;

  /* test: parsing valid response without body */
  buf =
    "HTTP/1.1 304 Not Modified\r\n"
    "Date: Thu, 14 Apr 2016 00:00:00 GMT\r\n"
    "Server: nginx/1.2.1\r\n"
    "Content-Length: 536\r\n"
    "\r\n";
  len = strlen(buf);
  res = new HTTPRes;
  assert((ret = res->parse(buf, len)) == len);
  assert(res->version == "HTTP/1.1");
  assert(res->status == "Not Modified");
  assert(res->code == 304);
  assert(res->headers.size() == 3);
  assert(res->headers.count("Date") == 1);
  assert(res->headers.count("Server") == 1);
  assert(res->headers.count("Content-Length") == 1);
  assert(res->headers.find("Date")->second == "Thu, 14 Apr 2016 00:00:00 GMT");
  assert(res->headers.find("Server")->second == "nginx/1.2.1");
  assert(res->headers.find("Content-Length")->second == "536");
  assert(res->is_chunked() == false);
  assert(res->content_length() == 536);
  delete res;

  /* test: building request */
  buf =
    "HTTP/1.0 304 Not Modified\r\n"
    "Content-Length: 0\r\n"
    "\r\n";
  res = new HTTPRes;
  res->version = "HTTP/1.0";
  res->code = 304;
  res->status = "Not Modified";
  res->add_header("Content-Length", "0");
  assert(res->to_string() == buf);

  /* test: case-insensitive header parsing (RFC 7230) */
  buf =
    "HTTP/1.1 200 OK\r\n"
    "content-type: text/html\r\n"
    "CONTENT-LENGTH: 42\r\n"
    "transfer-encoding: chunked\r\n"
    "content-encoding: gzip\r\n"
    "\r\n";
  len = strlen(buf);
  res = new HTTPRes;
  assert((ret = res->parse(buf, len)) == len);
  assert(res->code == 200);
  /* content_length() should work with any case */
  assert(res->content_length() == 42);
  /* is_chunked() should work with any case */
  assert(res->is_chunked() == true);
  /* is_gzipped() should work with any case */
  assert(res->is_gzipped() == true);
  delete res;

  /* test: add_header case-insensitivity (no duplicates) */
  res = new HTTPRes;
  res->add_header("Content-Type", "text/plain");
  res->add_header("content-type", "text/html"); /* should not add duplicate */
  assert(res->headers.size() == 1);
  res->add_header("content-type", "application/json", true); /* replace=true */
  assert(res->headers.size() == 1);
  delete res;

  /* test: del_header case-insensitivity */
  res = new HTTPRes;
  res->add_header("X-Custom-Header", "value");
  assert(res->headers.size() == 1);
  res->del_header("x-custom-header");
  assert(res->headers.size() == 0);
  delete res;

  /* test: to_string() doesn't add duplicate Date/Content-Length with different case */
  res = new HTTPRes;
  res->version = "HTTP/1.1";
  res->code = 200;
  res->status = "OK";
  res->add_header("date", "Thu, 01 Jan 1970 00:00:00 GMT");
  res->add_header("content-length", "0");
  res->body = "";
  std::string response = res->to_string();
  /* should not have duplicate headers */
  size_t date_count = 0, pos = 0;
  while ((pos = response.find("ate:", pos)) != std::string::npos) {
    date_count++;
    pos++;
  }
  assert(date_count == 1); /* only one Date header */
  delete res;

  /* test: get_header case-insensitivity (used by Reseed for Location redirect) */
  buf =
    "HTTP/1.1 302 Found\r\n"
    "location: https://example.com/new\r\n"
    "\r\n";
  len = strlen(buf);
  res = new HTTPRes;
  assert((ret = res->parse(buf, len)) == len);
  assert(res->get_header("Location") == "https://example.com/new");
  assert(res->get_header("location") == "https://example.com/new");
  assert(res->get_header("LOCATION") == "https://example.com/new");
  delete res;

  return 0;
}

/* vim: expandtab:ts=2 */
