// Without -Dultitools.withdrawVersion the goal stops before any request, even though the
// plugin configuration carries deploy's <version>2.0.0</version> and the confirmation names it.
def log = new File(basedir, 'build.log').getText('UTF-8')
assert log.contains('-Dultitools.withdrawVersion=') : 'explicit-version check did not run'
assert !log.contains('http://127.0.0.1:9') : 'a request was attempted'
return true
