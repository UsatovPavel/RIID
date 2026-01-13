# Structure:
<modulename> <- test which run exactly classes in module. Consider to refactor it to internal/<modulename> but tests use package private names
integration/<directory> <- tests checked many modules. (have Clashes with package-private conception of Java)
e2e/ <- end-to-end tests