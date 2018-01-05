/*
 * Copyright 2017 ThoughtWorks, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.thoughtworks.go.server.dao;

import com.thoughtworks.go.config.GoConfigDao;
import com.thoughtworks.go.database.Database;
import com.thoughtworks.go.domain.Pipeline;
import com.thoughtworks.go.domain.buildcause.BuildCause;
import com.thoughtworks.go.domain.materials.Modification;
import com.thoughtworks.go.helper.GoConfigMother;
import com.thoughtworks.go.helper.ModificationsMother;
import com.thoughtworks.go.presentation.pipelinehistory.PipelineInstanceModel;
import com.thoughtworks.go.presentation.pipelinehistory.PipelineInstanceModels;
import com.thoughtworks.go.presentation.pipelinehistory.StageInstanceModels;
import com.thoughtworks.go.server.cache.GoCache;
import com.thoughtworks.go.server.persistence.MaterialRepository;
import com.thoughtworks.go.util.SystemEnvironment;
import org.hibernate.SessionFactory;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.springframework.orm.ibatis.SqlMapClientTemplate;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Map;
import java.util.Random;

import static com.thoughtworks.go.helper.ModificationsMother.*;
import static com.thoughtworks.go.util.DataStructureUtils.m;
import static com.thoughtworks.go.util.IBatisUtil.arguments;
import static java.util.Arrays.asList;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.core.Is.is;
import static org.junit.Assert.assertTrue;
import static org.mockito.Matchers.any;
import static org.mockito.Matchers.anyString;
import static org.mockito.Matchers.eq;
import static org.mockito.Mockito.*;

public class PipelineSqlMapDaoTest {
    private PipelineSqlMapDao pipelineSqlMapDao;
    private GoCache goCache;
    private SqlMapClientTemplate sqlMapClientTemplate;
    private MaterialRepository materialRepository;
    private GoConfigDao configFileDao;

    @Before
    public void setUp() throws Exception {
        goCache = mock(GoCache.class);
        sqlMapClientTemplate = mock(SqlMapClientTemplate.class);
        materialRepository = mock(MaterialRepository.class);
        configFileDao = mock(GoConfigDao.class);
        pipelineSqlMapDao = new PipelineSqlMapDao(null, materialRepository, goCache, null, null, null, null, null, configFileDao, null, null);
        pipelineSqlMapDao.setSqlMapClientTemplate(sqlMapClientTemplate);
    }

    @Test
    public void shouldLoadPipelineHistoryFromCacheWhenQueriedViaNameAndCounter() throws Exception {
        String pipelineName = "wholetthedogsout";
        int pipelineCounter = 42;
        PipelineInstanceModel expected = mock(PipelineInstanceModel.class);
        when(goCache.get(anyString())).thenReturn(expected);

        PipelineInstanceModel reFetch = pipelineSqlMapDao.findPipelineHistoryByNameAndCounter(pipelineName, pipelineCounter); //returned from cache

        assertThat(reFetch, is(expected));
        verify(goCache).get(anyString());
    }

    @Test
    public void shouldPrimePipelineHistoryToCacheWhenQueriedViaNameAndCounter() throws Exception {
        String pipelineName = "wholetthedogsout";
        int pipelineCounter = 42;
        Map<String, Object> map = arguments("pipelineName", pipelineName).and("pipelineCounter", pipelineCounter).asMap();
        PipelineInstanceModel expected = mock(PipelineInstanceModel.class);
        when(sqlMapClientTemplate.queryForObject("getPipelineHistoryByNameAndCounter", map)).thenReturn(expected);
        when(expected.getId()).thenReturn(1111L);
        when(materialRepository.findMaterialRevisionsForPipeline(expected.getId())).thenReturn(null);

        PipelineInstanceModel primed = pipelineSqlMapDao.findPipelineHistoryByNameAndCounter(pipelineName, pipelineCounter);//prime cache

        assertThat(primed, is(expected));

        verify(sqlMapClientTemplate, times(1)).queryForObject("getPipelineHistoryByNameAndCounter", map);
        verify(goCache, times(1)).put(anyString(), eq(expected));
        verify(goCache, times(2)).get(anyString());
    }

    @Test
    public void shouldUpdateCommentAndRemoveItFromPipelineHistoryCache() throws Exception {
        String pipelineName = "wholetthedogsout";
        int pipelineCounter = 42;
        String comment = "This song is from the 90s.";
        Map<String, Object> args = arguments("pipelineName", pipelineName).and("pipelineCounter", pipelineCounter).and("comment", comment).asMap();

        Pipeline expected = mock(Pipeline.class);
        when(sqlMapClientTemplate.queryForObject("findPipelineByNameAndCounter", arguments("name", pipelineName).and("counter", pipelineCounter).asMap())).thenReturn(expected);
        when(expected.getId()).thenReturn(102413L);

        pipelineSqlMapDao.updateComment(pipelineName, pipelineCounter, comment);

        verify(sqlMapClientTemplate, times(1)).update("updatePipelineComment", args);
        verify(goCache, times(1)).remove("com.thoughtworks.go.server.dao.PipelineSqlMapDao_pipelineHistory_102413");
    }

    @Test
    public void shouldGetLatestRevisionFromOrderedLists() {
        PipelineSqlMapDao pipelineSqlMapDao = new PipelineSqlMapDao(null, null, null, null, null, null, null, new SystemEnvironment(), mock(GoConfigDao.class), mock(Database.class), mock(SessionFactory.class));
        ArrayList list1 = new ArrayList();
        ArrayList list2 = new ArrayList();
        Assert.assertThat(pipelineSqlMapDao.getLatestRevisionFromOrderedLists(list1, list2), is((String) null));
        Modification modification1 = new Modification(MOD_USER, MOD_COMMENT, EMAIL_ADDRESS,
                YESTERDAY_CHECKIN, ModificationsMother.nextRevision());
        list1.add(modification1);
        Assert.assertThat(pipelineSqlMapDao.getLatestRevisionFromOrderedLists(list1, list2), is(ModificationsMother.currentRevision()));
        Modification modification2 = new Modification(MOD_USER_COMMITTER, MOD_COMMENT_2, EMAIL_ADDRESS,
                TODAY_CHECKIN, ModificationsMother.nextRevision());
        list2.add(modification2);
        Assert.assertThat(pipelineSqlMapDao.getLatestRevisionFromOrderedLists(list1, list2), is(ModificationsMother.currentRevision()));
    }

    @Test
    public void loadHistoryByIds_shouldLoadHistoryByIdWhenOnlyASingleIdIsNeedeSoThatItUsesTheExistingCacheForEnvironmentsPage() throws Exception {
        SqlMapClientTemplate mockTemplate = mock(SqlMapClientTemplate.class);
        when(mockTemplate.queryForList(eq("getPipelineRange"), any())).thenReturn(Arrays.asList(2L));
        pipelineSqlMapDao.setSqlMapClientTemplate(mockTemplate);
        PipelineInstanceModels pipelineHistories = pipelineSqlMapDao.loadHistory("pipelineName", 1, 0);
        verify(mockTemplate, never()).queryForList(eq("getPipelineHistoryByName"), any());
        verify(mockTemplate, times(1)).queryForList(eq("getPipelineRange"), any());
    }

    @Test
    public void shouldGetAnEmptyListOfPIMsWhenActivePipelinesListDoesNotHavePIMsForRequestedPipeline() throws Exception {
        String pipelineName = "pipeline-with-no-active-instances";

        when(configFileDao.load()).thenReturn(GoConfigMother.configWithPipelines(pipelineName));
        when(sqlMapClientTemplate.queryForList("allActivePipelines")).thenReturn(new ArrayList<PipelineInstanceModel>());

        PipelineInstanceModels models = pipelineSqlMapDao.loadActivePipelineInstancesFor(pipelineName);

        assertTrue(models.isEmpty());
    }

    @Test
    public void shouldGetAnListOfPIMsForPipelineWhenActivePipelinesListHasPIMsForRequestedPipeline() throws Exception {
        String p1 = "pipeline-with-active-instances";
        String p2 = "pipeline-with-no-active-instances";

        PipelineInstanceModel pimForP1_1 = pimFor(p1, 1);
        PipelineInstanceModel pimForP1_2 = pimFor(p1, 2);

        when(configFileDao.load()).thenReturn(GoConfigMother.configWithPipelines(p1, p2));
        when(sqlMapClientTemplate.queryForList("allActivePipelines")).thenReturn(asList(pimForP1_1, pimForP1_2, pimFor(p2, 1), pimFor(p2, 2)));
        when(sqlMapClientTemplate.queryForObject("getPipelineHistoryById", m("id", pimForP1_1.getId()))).thenReturn(pimForP1_1);
        when(sqlMapClientTemplate.queryForObject("getPipelineHistoryById", m("id", pimForP1_2.getId()))).thenReturn(pimForP1_2);

        PipelineInstanceModels models = pipelineSqlMapDao.loadActivePipelineInstancesFor(p1);

        assertThat(models.size(), is(2));

        assertThat(pimForP1_1.getName(), is(p1));
        assertThat(pimForP1_1.getCounter(), is(1));

        assertThat(pimForP1_2.getName(), is(p1));
        assertThat(pimForP1_2.getCounter(), is(2));

        verify(sqlMapClientTemplate).queryForList("allActivePipelines");
        verify(sqlMapClientTemplate).queryForObject("getPipelineHistoryById", m("id", pimForP1_1.getId()));
        verify(sqlMapClientTemplate).queryForObject("getPipelineHistoryById", m("id", pimForP1_2.getId()));
        verifyNoMoreInteractions(sqlMapClientTemplate); /* Should not have loaded history for the other pipeline. */
    }

    private PipelineInstanceModel pimFor(String p1, int counter) {
        PipelineInstanceModel model = new PipelineInstanceModel(p1, counter, String.valueOf(counter), BuildCause.createManualForced(), new StageInstanceModels());
        model.setId(new Random().nextLong());
        return model;
    }
}