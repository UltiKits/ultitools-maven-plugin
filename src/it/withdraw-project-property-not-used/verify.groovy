// The ultitools.version project property (the framework version in module POMs) is not a
// withdraw version: without -Dultitools.withdrawVersion the goal stops before any request.
def log = new File(basedir, 'build.log').getText('UTF-8')
assert log.contains('-Dultitools.withdrawVersion=') : 'explicit-version check did not run'
assert !log.contains('http://127.0.0.1:9') : 'a request was attempted'
return true
