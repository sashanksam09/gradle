/*
 * Copyright 2014 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.gradle.integtests.resolve

import org.gradle.integtests.fixtures.GradleMetadataResolveRunner
import org.gradle.integtests.fixtures.RequiredFeature
import org.gradle.integtests.fixtures.RequiredFeatures

class ComponentMetadataRulesIntegrationTest extends AbstractModuleDependencyResolveTest implements ComponentMetadataRulesSupport {
    String getDefaultStatus() {
        GradleMetadataResolveRunner.useIvy()?'integration':'release'
    }

    def setup() {
        buildFile <<
"""
dependencies {
    conf 'org.test:projectA:1.0'
}

// implement Sync manually to make sure that task is never up-to-date
task resolve {
    doLast {
        delete 'libs'
        copy {
            from configurations.conf
            into 'libs'
        }
    }
}
"""
    }

    def "rule receives correct metadata"() {
        repository {
            'org.test:projectA:1.0'()
        }
        buildFile <<
"""
dependencies {
    components {
        all { ComponentMetadataDetails details ->
            assert details.id.group == "org.test"
            assert details.id.name == "projectA"
            assert details.id.version == "1.0"
            assert details.status == "$defaultStatus"
            assert details.statusScheme == ["integration", "milestone", "release"]
            assert !details.changing
        }
    }
}
"""

        when:
        repositoryInteractions {
            'org.test:projectA:1.0' {
                allowAll()
            }
        }

        then:
        succeeds 'resolve'
    }

    def "changes made by a rule are visible to subsequent rules"() {
        repository {
            'org.test:projectA:1.0'()
        }

        buildFile <<
                """
dependencies {
    components {
        all { ComponentMetadataDetails details ->
            details.status "integration.changed" // verify that 'details' is enhanced
            details.statusScheme = ["integration.changed", "milestone.changed", "release.changed"]
            details.changing = true
        }
        all { ComponentMetadataDetails details ->
            assert details.status == "integration.changed"
            assert details.statusScheme == ["integration.changed", "milestone.changed", "release.changed"]
            assert details.changing
        }
    }
}
"""

        when:
        repositoryInteractions {
            'org.test:projectA:1.0' {
                allowAll()
            }
        }

        then:
        succeeds 'resolve'
    }

    def "changes made by a rule are not cached"() {
        repository {
            'org.test:projectA:1.0'()
        }

        buildFile <<
                """
dependencies {
    components {
        all { ComponentMetadataDetails details ->
            assert !details.changing
            assert details.status == "$defaultStatus"
            assert details.statusScheme == ["integration", "milestone", "release"]

            details.changing = true
            details.status = "release.changed"
            details.statusScheme = ["integration.changed", "milestone.changed", "release.changed"]
        }
    }
}
"""

        when:
        repositoryInteractions {
            'org.test:projectA:1.0' {
                allowAll()
            }
        }

        then:
        succeeds 'resolve'
        succeeds 'resolve'
    }

    def "can apply all rule types to all modules" () {
        repository {
            'org.test:projectA:1.0'()
        }
        buildFile << """
            ext.rulesInvoked = []
            dependencies {
                components {
                    all { ComponentMetadataDetails details ->
                        rulesInvoked << details.id.version
                    }
                    all {
                        rulesInvoked << id.version
                    }
                    all { details ->
                        rulesInvoked << details.id.version
                    }
                    all(new ActionRule('rulesInvoked': rulesInvoked))
                    all(new RuleObject('rulesInvoked': rulesInvoked))
                }
            }

            class ActionRule implements Action<ComponentMetadataDetails> {
                List rulesInvoked

                void execute(ComponentMetadataDetails details) {
                    rulesInvoked << details.id.version
                }
            }

            class RuleObject {
                List rulesInvoked

                @org.gradle.model.Mutate
                void execute(ComponentMetadataDetails details) {
                    rulesInvoked << details.id.version
                }
            }

            resolve.doLast { assert rulesInvoked == [ '1.0', '1.0', '1.0', '1.0', '1.0' ] }
        """

        when:
        repositoryInteractions {
            'org.test:projectA:1.0' {
                allowAll()
            }
        }

        then:
        succeeds 'resolve'
    }

    def "can apply all rule types by module" () {
        repository {
            'org.test:projectA:1.0'()
        }
        buildFile << """
            ext.rulesInvoked = []
            ext.rulesUninvoked = []
            dependencies {
                components {
                    withModule('org.test:projectA') { ComponentMetadataDetails details ->
                        assert details.id.group == 'org.test'
                        assert details.id.name == 'projectA'
                        rulesInvoked << 1
                    }
                    withModule('org.test:projectA', new ActionRule('rulesInvoked': rulesInvoked))
                    withModule('org.test:projectA', new RuleObject('rulesInvoked': rulesInvoked))

                    withModule('org.test:projectB') { ComponentMetadataDetails details ->
                        rulesUninvoked << 1
                    }
                    withModule('org.test:projectB', new ActionRule('rulesInvoked': rulesUninvoked))
                    withModule('org.test:projectB', new RuleObject('rulesInvoked': rulesUninvoked))
                }
            }

            class ActionRule implements Action<ComponentMetadataDetails> {
                List rulesInvoked

                void execute(ComponentMetadataDetails details) {
                    rulesInvoked << 2
                }
            }

            class RuleObject {
                List rulesInvoked

                @org.gradle.model.Mutate
                void execute(ComponentMetadataDetails details) {
                    rulesInvoked << 3
                }
            }

            resolve.doLast {
                assert rulesInvoked.sort() == [ 1, 2, 3 ]
                assert rulesUninvoked.empty
            }
        """

        when:
        repositoryInteractions {
            'org.test:projectA:1.0' {
                allowAll()
            }
        }

        then:
        succeeds 'resolve'
    }

    def "produces sensible error when @Mutate method does not have ComponentMetadata as first parameter"() {
        buildFile << """
            dependencies {
                components {
                    all(new BadRuleSource())
                }
            }

            class BadRuleSource {
                @org.gradle.model.Mutate
                void doSomething(String s) { }
            }
        """

        when:
        fails "resolve"

        then:
        fails 'resolveConf'
        failureDescriptionStartsWith("A problem occurred evaluating root project")
        failure.assertHasCause("""Type BadRuleSource is not a valid rule source:
- Method doSomething(java.lang.String) is not a valid rule method: First parameter of a rule method must be of type org.gradle.api.artifacts.ComponentMetadataDetails""")
    }

    @RequiredFeatures(
        @RequiredFeature(feature=GradleMetadataResolveRunner.REPOSITORY_TYPE, value="maven")
    )
    def "rule that accepts IvyModuleDescriptor isn't invoked for Maven component"() {
        given:
        repository {
            'org.test:projectA:1.0'()
        }

        buildFile <<
            """
def plainRuleInvoked = false
def ivyRuleInvoked = false

dependencies {
    components {
        all { ComponentMetadataDetails details ->
            plainRuleInvoked = true
        }
        all { ComponentMetadataDetails details, IvyModuleDescriptor descriptor ->
            ivyRuleInvoked = true
        }
    }
}

resolve.doLast {
    assert plainRuleInvoked
    assert !ivyRuleInvoked
}
"""
        when:
        repositoryInteractions {
            'org.test:projectA:1.0' {
                expectResolve()
            }
        }

        then:
        succeeds 'resolve'
        // also works when already cached
        succeeds 'resolve'
    }

}
