/*
 *
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 *
 */
define(["dojo/_base/xhr", "dojo/parser", "dojox/html/entities", "dojo/query", "dojo/_base/lang", "dojo/domReady!"],
    function (xhr, parser, entities, query, lang)
    {
        var fieldNames = ["maxConnectionsPerPartition", "minConnectionsPerPartition", "partitionCount"];

        function BoneCP(data)
        {
            var containerNode = data.containerNode;
            this.parent = data.parent;
            var that = this;
            xhr.get({
                url: "store/pool/bonecp/show.html",
                sync: true,
                load: function (template)
                {
                    containerNode.innerHTML = template;
                    parser.parse(containerNode)
                        .then(function (instances)
                        {
                            for (var i = 0; i < fieldNames.length; i++)
                            {
                                var fieldName = fieldNames[i];
                                that[fieldName] = query("." + fieldName, containerNode)[0];
                            }
                        });
                }
            });
        }

        BoneCP.prototype.update = function (data)
        {
            this.parent.management.load(this.parent.modelObj,
                {
                    excludeInheritedContext: false,
                    depth: 0
                }).then(lang.hitch(this, function (inheritedData)
            {
                if (inheritedData && inheritedData[0])
                {
                    var context = inheritedData[0].context;
                    for (var i = 0; i < fieldNames.length; i++)
                    {
                        var fieldName = fieldNames[i];
                        var value = context ? context["qpid.jdbcstore.bonecp." + fieldName] : "";
                        this[fieldName].innerHTML = value ? entities.encode(String(value)) : "";
                    }
                }
            }));

        };

        return BoneCP;
    });
