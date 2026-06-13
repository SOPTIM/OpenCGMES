# Bundled CGMES 3.0 RDFS profiles

These `.rdf` files are the CGMES 3.0 (IEC 61970-600-2) application-profile RDFS
vocabularies, vendored verbatim from the ENTSO-E **Application Profiles Library**:

- Source: https://github.com/entsoe/application-profiles-library
  (`CGMES/CurrentRelease/RDFS/`)
- License: Apache License 2.0 — see `LICENSE.txt` and `NOTICE.txt` in this directory.

CIMcheck bundles them so validation works out of the box with **zero configuration**:
when no `opencgmes.json` is found (or one is found that does not specify `schemas` /
`schemasDirectory`), these profiles are used as the default schema.

`manifest.txt` lists the bundled `.rdf` files and is read at runtime by
`BundledSchemas` to extract them into the user cache directory.

To update: re-copy the `.rdf` files from the upstream `CGMES/CurrentRelease/RDFS/`
directory and regenerate `manifest.txt` (`ls *.rdf | sort > manifest.txt`).
