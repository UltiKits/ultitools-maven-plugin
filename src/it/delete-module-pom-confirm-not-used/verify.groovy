// A confirmation written into the POM (plugin configuration or a project property) is not a
// confirmation: without -Dultitools.confirm on the command line the goal stops before any request.
def log = new File(basedir, 'build.log').getText('UTF-8')
assert log.contains('缺少确认串') : 'the POM-supplied confirmation was accepted'
assert !log.contains('http://127.0.0.1:9') : 'a request was attempted'
return true
