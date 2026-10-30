# F-Droid metadata

The application recipe is maintained in [F-Droid Data](https://gitlab.com/fdroid/fdroiddata/-/merge_requests/45667). Use that authoritative recipe and its pinned application and source-library commits.

This repository does not keep a second build recipe: embedding the application commit in its own recipe would change that commit. Release qualification uses the external recipe for the final source commit and a separate reproducible source build.
