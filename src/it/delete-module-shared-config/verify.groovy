// delete-module shares identifyString/accessKeyFile/apiUrl with deploy on purpose; deploy's
// other settings do not disturb its confirmation, which goes on to the (unreachable) API.
def log = new File(basedir, 'build.log').getText('UTF-8')
assert !log.contains('-Dultitools.confirm=uc-it') : 'confirmation was refused'
assert log.contains('http://127.0.0.1:9') : 'goal did not reach the API'
return true
