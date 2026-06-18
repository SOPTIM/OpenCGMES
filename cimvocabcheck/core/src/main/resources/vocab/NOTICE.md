# Third-party W3C vocabularies

This directory contains W3C standard vocabulary documents, vendored so CIMVocabCheck can type-check
`rdf:`, `rdfs:`, `owl:`, and `sh:` terms offline (see `StandardVocabulary`). These files are
**not** part of CIMVocabCheck: they are W3C works, redistributed here under W3C's license, separate
from CIMVocabCheck's own Apache License 2.0.

| File        | Vocabulary                         | Source document |
| ----------- | ---------------------------------- | --------------- |
| `rdf.ttl`   | RDF 1.1 Concepts vocabulary        | <https://www.w3.org/1999/02/22-rdf-syntax-ns#> |
| `rdfs.ttl`  | RDF Schema 1.1 vocabulary          | <https://www.w3.org/2000/01/rdf-schema#> |
| `owl.ttl`   | OWL 2 Schema vocabulary            | <https://www.w3.org/2002/07/owl#> |
| `shacl.ttl` | SHACL (Shapes Constraint Language) | <https://www.w3.org/ns/shacl#> |

They are vendored from the W3C namespace / specification documents at the URLs above (Turtle
serialization). Each file's authoritative content and status are defined by its source document.

Copyright © World Wide Web Consortium.
<https://www.w3.org/>

These documents are made available by W3C under the **W3C Software and Document License (2023)**:
<https://www.w3.org/copyright/software-license-2023/>
and the **W3C Document License (2023)**:
<https://www.w3.org/copyright/document-license-2023/>

Per those licenses, this notice retains the W3C copyright and links to the original documents.
For the authoritative, up-to-date vocabularies, refer to the source URLs above.
