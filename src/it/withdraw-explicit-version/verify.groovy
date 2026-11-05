// Shared <version>2.0.0</version> and the ultitools.version project property must not
// replace the version given on the command line: the confirmation for 1.2.3 is accepted
// and the goal goes on to the (unreachable) API.
def log = new File(basedir, 'build.log').getText('UTF-8')
assert !log.contains('-Dultitools.confirm=uc-it@') : 'confirmation was refused: the version came from elsewhere'
assert log.contains('http://127.0.0.1:9') && log.contains('uc-it@1.2.3') : 'goal did not reach the API for uc-it@1.2.3'
assert !log.contains('uc-it@2.0.0') && !log.contains('uc-it@6.3.0')
return true
