// A withdraw version written into the POM (plugin configuration or a project property) is not
// used: without -Dultitools.withdrawVersion on the command line the goal stops before any request.
def log = new File(basedir, 'build.log').getText('UTF-8')
assert log.contains('撤回需要显式给出版本') : 'the POM-supplied withdraw version was used'
assert !log.contains('http://127.0.0.1:9') : 'a request was attempted'
return true
