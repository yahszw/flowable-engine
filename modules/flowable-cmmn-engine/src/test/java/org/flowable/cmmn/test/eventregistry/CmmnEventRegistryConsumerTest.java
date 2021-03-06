/* Licensed under the Apache License, Version 2.0 (the "License");
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
package org.flowable.cmmn.test.eventregistry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.entry;

import java.util.List;
import java.util.Random;

import org.flowable.cmmn.api.repository.CaseDefinition;
import org.flowable.cmmn.api.runtime.CaseInstance;
import org.flowable.cmmn.engine.test.CmmnDeployment;
import org.flowable.cmmn.engine.test.FlowableCmmnTestCase;
import org.flowable.common.engine.api.constant.ReferenceTypes;
import org.flowable.common.engine.api.scope.ScopeTypes;
import org.flowable.eventregistry.api.EventDeployment;
import org.flowable.eventregistry.api.EventRegistry;
import org.flowable.eventregistry.api.EventRepositoryService;
import org.flowable.eventregistry.api.InboundEventChannelAdapter;
import org.flowable.eventregistry.api.model.EventPayloadTypes;
import org.flowable.eventregistry.model.InboundChannelModel;
import org.flowable.eventsubscription.api.EventSubscription;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * @author Joram Barrez
 * @author Filip Hrisafov
 */
public class CmmnEventRegistryConsumerTest extends FlowableCmmnTestCase {

    protected TestInboundEventChannelAdapter inboundEventChannelAdapter;

    @Before
    public void registerEventDefinition() {
        inboundEventChannelAdapter = setupTestChannel();

        getEventRepositoryService().createEventModelBuilder()
            .inboundChannelKey("test-channel")
            .key("myEvent")
            .resourceName("myEvent.event")
            .correlationParameter("customerId", EventPayloadTypes.STRING)
            .correlationParameter("orderId", EventPayloadTypes.STRING)
            .payload("payload1", EventPayloadTypes.STRING)
            .payload("payload2", EventPayloadTypes.INTEGER)
            .deploy();
    }

    protected TestInboundEventChannelAdapter setupTestChannel() {
        getEventRepositoryService().createInboundChannelModelBuilder()
            .key("test-channel")
            .resourceName("test.channel")
            .jmsChannelAdapter("test")
            .eventProcessingPipeline()
            .jsonDeserializer()
            .detectEventKeyUsingJsonField("type")
            .jsonFieldsMapDirectlyToPayload()
            .deploy();
        
        TestInboundEventChannelAdapter inboundEventChannelAdapter = new TestInboundEventChannelAdapter();
        InboundChannelModel inboundChannelModel = (InboundChannelModel) getEventRepositoryService().getChannelModelByKey("test-channel");
        inboundChannelModel.setInboundEventChannelAdapter(inboundEventChannelAdapter);
        
        inboundEventChannelAdapter.setEventRegistry(getEventRegistry());
        inboundEventChannelAdapter.setInboundChannelModel(inboundChannelModel);

        return inboundEventChannelAdapter;
    }


    @After
    public void unregisterEventDefinition() {
        EventRepositoryService eventRepositoryService = getEventRepositoryService();
        List<EventDeployment> deployments = eventRepositoryService.createDeploymentQuery().list();
        for (EventDeployment eventDeployment : deployments) {
            eventRepositoryService.deleteDeployment(eventDeployment.getId());
        }
    }

    @Test
    @CmmnDeployment
    public void testGenericEventListenerNoCorrelation() {
        CaseInstance caseInstance = cmmnRuntimeService.createCaseInstanceBuilder().caseDefinitionKey("myCase").start();
        assertThat(cmmnTaskService.createTaskQuery().caseInstanceId(caseInstance.getId()).list()).hasSize(1);

        for (int i = 2; i < 10; i++) {
            inboundEventChannelAdapter.triggerTestEvent("test");
            assertThat(cmmnTaskService.createTaskQuery().caseInstanceId(caseInstance.getId()).list()).hasSize(i);
        }
    }

    @Test
    @CmmnDeployment
    public void testGenericEventListenerWithCorrelation() {
        CaseInstance kermitCase = cmmnRuntimeService.createCaseInstanceBuilder()
            .caseDefinitionKey("singleCorrelationCase")
            .variable("customerIdVar", "kermit")
            .start();
        CaseInstance gonzoCase = cmmnRuntimeService.createCaseInstanceBuilder()
            .caseDefinitionKey("singleCorrelationCase")
            .variable("customerIdVar", "gonzo")
            .start();
        assertThat(cmmnTaskService.createTaskQuery().caseInstanceId(kermitCase.getId()).list()).hasSize(1);
        assertThat(cmmnTaskService.createTaskQuery().caseInstanceId(gonzoCase.getId()).list()).hasSize(1);

        inboundEventChannelAdapter.triggerTestEvent("kermit");
        assertThat(cmmnTaskService.createTaskQuery().caseInstanceId(kermitCase.getId()).list()).hasSize(2);
        assertThat(cmmnTaskService.createTaskQuery().caseInstanceId(gonzoCase.getId()).list()).hasSize(1);

        inboundEventChannelAdapter.triggerTestEvent("gonzo");
        assertThat(cmmnTaskService.createTaskQuery().caseInstanceId(kermitCase.getId()).list()).hasSize(2);
        assertThat(cmmnTaskService.createTaskQuery().caseInstanceId(gonzoCase.getId()).list()).hasSize(2);

        inboundEventChannelAdapter.triggerTestEvent("fozzie");
        assertThat(cmmnTaskService.createTaskQuery().caseInstanceId(kermitCase.getId()).list()).hasSize(2);
        assertThat(cmmnTaskService.createTaskQuery().caseInstanceId(gonzoCase.getId()).list()).hasSize(2);
    }

    @Test
    @CmmnDeployment(resources = {
        "org/flowable/cmmn/test/eventregistry/CmmnEventRegistryConsumerTest.testGenericEventListenerNoCorrelation.cmmn",
        "org/flowable/cmmn/test/eventregistry/CmmnEventRegistryConsumerTest.testGenericEventListenerWithCorrelation.cmmn",
        "org/flowable/cmmn/test/eventregistry/CmmnEventRegistryConsumerTest.testGenericEventListenerWithOrderCorrelation.cmmn",
        "org/flowable/cmmn/test/eventregistry/CmmnEventRegistryConsumerTest.testGenericEventListenerWithTwoCorrelations.cmmn"
    })
    public void testGenericEventListenerWithMultipleCorrelations() {
        CaseInstance noCorrelationCase = cmmnRuntimeService.createCaseInstanceBuilder()
            .caseDefinitionKey("myCase")
            .start();
        CaseInstance kermitOrder1Case = cmmnRuntimeService.createCaseInstanceBuilder()
            .caseDefinitionKey("twoCorrelationsCase")
            .variable("customerIdVar", "kermit")
            .variable("orderIdVar", "order1")
            .start();
        CaseInstance kermitOrder2Case = cmmnRuntimeService.createCaseInstanceBuilder()
            .caseDefinitionKey("twoCorrelationsCase")
            .variable("customerIdVar", "kermit")
            .variable("orderIdVar", "order2")
            .start();
        CaseInstance gonzoOrder1Case = cmmnRuntimeService.createCaseInstanceBuilder()
            .caseDefinitionKey("twoCorrelationsCase")
            .variable("customerIdVar", "gonzo")
            .variable("orderIdVar", "order1")
            .start();
        CaseInstance gonzoCase = cmmnRuntimeService.createCaseInstanceBuilder()
            .caseDefinitionKey("singleCorrelationCase")
            .variable("customerIdVar", "gonzo")
            .start();
        CaseInstance order1Case = cmmnRuntimeService.createCaseInstanceBuilder()
            .caseDefinitionKey("orderCorrelationCase")
            .variable("orderIdVar", "order1")
            .start();
        CaseInstance order2Case = cmmnRuntimeService.createCaseInstanceBuilder()
            .caseDefinitionKey("orderCorrelationCase")
            .variable("orderIdVar", "order2")
            .start();

        assertThat(cmmnTaskService.createTaskQuery().caseInstanceId(noCorrelationCase.getId()).list()).hasSize(1);
        assertThat(cmmnTaskService.createTaskQuery().caseInstanceId(kermitOrder1Case.getId()).list()).hasSize(1);
        assertThat(cmmnTaskService.createTaskQuery().caseInstanceId(kermitOrder2Case.getId()).list()).hasSize(1);
        assertThat(cmmnTaskService.createTaskQuery().caseInstanceId(gonzoOrder1Case.getId()).list()).hasSize(1);
        assertThat(cmmnTaskService.createTaskQuery().caseInstanceId(gonzoCase.getId()).list()).hasSize(1);
        assertThat(cmmnTaskService.createTaskQuery().caseInstanceId(order1Case.getId()).list()).hasSize(1);
        assertThat(cmmnTaskService.createTaskQuery().caseInstanceId(order2Case.getId()).list()).hasSize(1);

        inboundEventChannelAdapter.triggerTestEvent("kermit");
        assertThat(cmmnTaskService.createTaskQuery().caseInstanceId(noCorrelationCase.getId()).list()).hasSize(2);
        assertThat(cmmnTaskService.createTaskQuery().caseInstanceId(kermitOrder1Case.getId()).list()).hasSize(1);
        assertThat(cmmnTaskService.createTaskQuery().caseInstanceId(kermitOrder2Case.getId()).list()).hasSize(1);
        assertThat(cmmnTaskService.createTaskQuery().caseInstanceId(gonzoOrder1Case.getId()).list()).hasSize(1);
        assertThat(cmmnTaskService.createTaskQuery().caseInstanceId(gonzoCase.getId()).list()).hasSize(1);
        assertThat(cmmnTaskService.createTaskQuery().caseInstanceId(order1Case.getId()).list()).hasSize(1);
        assertThat(cmmnTaskService.createTaskQuery().caseInstanceId(order2Case.getId()).list()).hasSize(1);

        inboundEventChannelAdapter.triggerTestEvent("gonzo");
        assertThat(cmmnTaskService.createTaskQuery().caseInstanceId(noCorrelationCase.getId()).list()).hasSize(3);
        assertThat(cmmnTaskService.createTaskQuery().caseInstanceId(kermitOrder1Case.getId()).list()).hasSize(1);
        assertThat(cmmnTaskService.createTaskQuery().caseInstanceId(kermitOrder2Case.getId()).list()).hasSize(1);
        assertThat(cmmnTaskService.createTaskQuery().caseInstanceId(gonzoOrder1Case.getId()).list()).hasSize(1);
        assertThat(cmmnTaskService.createTaskQuery().caseInstanceId(gonzoCase.getId()).list()).hasSize(2);
        assertThat(cmmnTaskService.createTaskQuery().caseInstanceId(order1Case.getId()).list()).hasSize(1);
        assertThat(cmmnTaskService.createTaskQuery().caseInstanceId(order2Case.getId()).list()).hasSize(1);

        inboundEventChannelAdapter.triggerTestEvent("fozzie");
        assertThat(cmmnTaskService.createTaskQuery().caseInstanceId(noCorrelationCase.getId()).list()).hasSize(4);
        assertThat(cmmnTaskService.createTaskQuery().caseInstanceId(kermitOrder1Case.getId()).list()).hasSize(1);
        assertThat(cmmnTaskService.createTaskQuery().caseInstanceId(kermitOrder2Case.getId()).list()).hasSize(1);
        assertThat(cmmnTaskService.createTaskQuery().caseInstanceId(gonzoOrder1Case.getId()).list()).hasSize(1);
        assertThat(cmmnTaskService.createTaskQuery().caseInstanceId(gonzoCase.getId()).list()).hasSize(2);
        assertThat(cmmnTaskService.createTaskQuery().caseInstanceId(order1Case.getId()).list()).hasSize(1);
        assertThat(cmmnTaskService.createTaskQuery().caseInstanceId(order2Case.getId()).list()).hasSize(1);

        inboundEventChannelAdapter.triggerTestEvent("kermit", "order1");
        assertThat(cmmnTaskService.createTaskQuery().caseInstanceId(noCorrelationCase.getId()).list()).hasSize(5);
        assertThat(cmmnTaskService.createTaskQuery().caseInstanceId(kermitOrder1Case.getId()).list()).hasSize(2);
        assertThat(cmmnTaskService.createTaskQuery().caseInstanceId(kermitOrder2Case.getId()).list()).hasSize(1);
        assertThat(cmmnTaskService.createTaskQuery().caseInstanceId(gonzoOrder1Case.getId()).list()).hasSize(1);
        assertThat(cmmnTaskService.createTaskQuery().caseInstanceId(gonzoCase.getId()).list()).hasSize(2);
        assertThat(cmmnTaskService.createTaskQuery().caseInstanceId(order1Case.getId()).list()).hasSize(2);
        assertThat(cmmnTaskService.createTaskQuery().caseInstanceId(order2Case.getId()).list()).hasSize(1);

        inboundEventChannelAdapter.triggerTestEvent("gonzo", "order1");
        assertThat(cmmnTaskService.createTaskQuery().caseInstanceId(noCorrelationCase.getId()).list()).hasSize(6);
        assertThat(cmmnTaskService.createTaskQuery().caseInstanceId(kermitOrder1Case.getId()).list()).hasSize(2);
        assertThat(cmmnTaskService.createTaskQuery().caseInstanceId(kermitOrder2Case.getId()).list()).hasSize(1);
        assertThat(cmmnTaskService.createTaskQuery().caseInstanceId(gonzoOrder1Case.getId()).list()).hasSize(2);
        assertThat(cmmnTaskService.createTaskQuery().caseInstanceId(gonzoCase.getId()).list()).hasSize(3);
        assertThat(cmmnTaskService.createTaskQuery().caseInstanceId(order1Case.getId()).list()).hasSize(3);
        assertThat(cmmnTaskService.createTaskQuery().caseInstanceId(order2Case.getId()).list()).hasSize(1);

        inboundEventChannelAdapter.triggerOrderTestEvent("order2");
        assertThat(cmmnTaskService.createTaskQuery().caseInstanceId(noCorrelationCase.getId()).list()).hasSize(7);
        assertThat(cmmnTaskService.createTaskQuery().caseInstanceId(kermitOrder1Case.getId()).list()).hasSize(2);
        assertThat(cmmnTaskService.createTaskQuery().caseInstanceId(kermitOrder2Case.getId()).list()).hasSize(1);
        assertThat(cmmnTaskService.createTaskQuery().caseInstanceId(gonzoOrder1Case.getId()).list()).hasSize(2);
        assertThat(cmmnTaskService.createTaskQuery().caseInstanceId(gonzoCase.getId()).list()).hasSize(3);
        assertThat(cmmnTaskService.createTaskQuery().caseInstanceId(order1Case.getId()).list()).hasSize(3);
        assertThat(cmmnTaskService.createTaskQuery().caseInstanceId(order2Case.getId()).list()).hasSize(2);
    }

    @Test
    @CmmnDeployment
    public void testCaseStartNoCorrelationParameter() {
        CaseDefinition caseDefinition = cmmnRepositoryService.createCaseDefinitionQuery().caseDefinitionKey("testCaseStartEvent").singleResult();
        assertThat(caseDefinition).isNotNull();

        EventSubscription eventSubscription = cmmnRuntimeService.createEventSubscriptionQuery()
            .scopeDefinitionId(caseDefinition.getId())
            .scopeType(ScopeTypes.CMMN)
            .singleResult();
        assertThat(eventSubscription).isNotNull();
        assertThat(eventSubscription.getEventType()).isEqualTo("myEvent");

        assertThat(cmmnRuntimeService.createCaseInstanceQuery().list()).hasSize(0);

        for (int i = 1; i <= 5; i++) {
            inboundEventChannelAdapter.triggerTestEvent();
            assertThat(cmmnRuntimeService.createCaseInstanceQuery().list()).hasSize(i);
        }
    }

    @Test
    @CmmnDeployment
    public void testCaseStartWithSimpleCorrelationParameter() {
        CaseDefinition caseDefinition = cmmnRepositoryService.createCaseDefinitionQuery().caseDefinitionKey("testCaseStartEvent").singleResult();
        assertThat(caseDefinition).isNotNull();

        EventSubscription eventSubscription = cmmnRuntimeService.createEventSubscriptionQuery()
            .scopeDefinitionId(caseDefinition.getId())
            .scopeType(ScopeTypes.CMMN)
            .singleResult();
        assertThat(eventSubscription).isNotNull();
        assertThat(eventSubscription.getEventType()).isEqualTo("myEvent");

        assertThat(cmmnRuntimeService.createCaseInstanceQuery().list()).hasSize(0);

        inboundEventChannelAdapter.triggerTestEvent("anotherCustomer");
        assertThat(cmmnRuntimeService.createCaseInstanceQuery().list()).hasSize(0); // shouldn't trigger, correlation doesn't match

        for (int i = 1; i <= 3; i++) {
            inboundEventChannelAdapter.triggerTestEvent("testCustomer");
            assertThat(cmmnRuntimeService.createCaseInstanceQuery().list()).hasSize(i);
        }
    }

    @Test
    @CmmnDeployment
    public void testGenericEventListenerWithPayload() {
        CaseInstance caseInstance = cmmnRuntimeService.createCaseInstanceBuilder()
            .caseDefinitionKey("testCaseEventWithPayload")
            .start();

        assertThat(cmmnRuntimeService.getVariables(caseInstance.getId())).isEmpty();
        inboundEventChannelAdapter.triggerTestEvent("payloadCustomer");
        assertThat(cmmnRuntimeService.getVariables(caseInstance.getId()))
            .containsOnly(
                entry("customerIdVar", "payloadCustomer"),
                entry("payload1", "Hello World")
            );
    }

    @Test
    @CmmnDeployment
    public void testCaseStartWithPayload() {
        CaseDefinition caseDefinition = cmmnRepositoryService.createCaseDefinitionQuery().caseDefinitionKey("testCaseStartEventWithPayload").singleResult();
        assertThat(caseDefinition).isNotNull();

        inboundEventChannelAdapter.triggerTestEvent("payloadStartCustomer");
        assertThat(cmmnRuntimeService.createCaseInstanceQuery().list()).hasSize(1);

        CaseInstance caseInstance = cmmnRuntimeService.createCaseInstanceQuery().singleResult();

        assertThat(cmmnRuntimeService.getVariables(caseInstance.getId()))
            .containsOnly(
                entry("customerIdVar", "payloadStartCustomer"),
                entry("anotherVarName", "Hello World")
            );
    }

    @Test
    @CmmnDeployment
    public void testCaseStartOnlyOneInstance() {
        for (int i = 1; i <= 9; i++) {
            inboundEventChannelAdapter.triggerTestEvent("testCustomer");
            assertThat(cmmnRuntimeService.createCaseInstanceQuery().list()).hasSize(1);
        }
        assertThat(cmmnRuntimeService.createCaseInstanceQuery().singleResult().getReferenceId()).isNotNull();
        assertThat(cmmnRuntimeService.createCaseInstanceQuery().singleResult().getReferenceType()).isEqualTo(ReferenceTypes.EVENT_CASE);

        for (int i = 1; i <= 4; i++) {
            inboundEventChannelAdapter.triggerTestEvent("anotherCustomer");
            assertThat(cmmnRuntimeService.createCaseInstanceQuery().list()).hasSize(2);
        }
    }

    private static class TestInboundEventChannelAdapter implements InboundEventChannelAdapter {

        public InboundChannelModel inboundChannelModel;
        public EventRegistry eventRegistry;

        @Override
        public void setInboundChannelModel(InboundChannelModel inboundChannelModel) {
            this.inboundChannelModel = inboundChannelModel;
        }

        @Override
        public void setEventRegistry(EventRegistry eventRegistry) {
            this.eventRegistry = eventRegistry;
        }

        public void triggerTestEvent() {
            triggerTestEvent(null);
        }

        public void triggerTestEvent(String customerId) {
            triggerTestEvent(customerId, null);
        }

        public void triggerOrderTestEvent(String orderId) {
            triggerTestEvent(null, orderId);
        }

        public void triggerTestEvent(String customerId, String orderId) {
            ObjectMapper objectMapper = new ObjectMapper();

            ObjectNode json = objectMapper.createObjectNode();
            json.put("type", "myEvent");
            if (customerId != null) {
                json.put("customerId", customerId);
            }

            if (orderId != null) {
                json.put("orderId", orderId);
            }
            json.put("payload1", "Hello World");
            json.put("payload2", new Random().nextInt());
            try {
                eventRegistry.eventReceived(inboundChannelModel, objectMapper.writeValueAsString(json));
            } catch (JsonProcessingException e) {
                throw new RuntimeException(e);
            }
        }

    }

}
