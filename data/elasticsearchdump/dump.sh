#!/bin/bash

elasticdump \
  --input=http://localhost:9200/viwiki_entity \
  --output=/TeBaQA/data/elasticsearchdump/viwiki_entity_mapping.json \
  --type=mapping
elasticdump \
  --input=http://localhost:9200/viwiki_entity \
  --output=/TeBaQA/data/elasticsearchdump/viwiki_entity_index.json \
  --type=data
elasticdump \
  --input=http://localhost:9200/viwiki_class \
  --output=/TeBaQA/data/elasticsearchdump/viwiki_class_mapping.json \
  --type=mapping
elasticdump \
  --input=http://localhost:9200/viwiki_class \
  --output=/TeBaQA/data/elasticsearchdump/viwiki_class_index.json \
  --type=data
elasticdump \
  --input=http://localhost:9200/viwiki_property \
  --output=/TeBaQA/data/elasticsearchdump/viwiki_property_mapping.json \
  --type=mapping
elasticdump \
  --input=http://localhost:9200/viwiki_property \
  --output=/TeBaQA/data/elasticsearchdump/viwiki_property_index.json \
  --type=data

