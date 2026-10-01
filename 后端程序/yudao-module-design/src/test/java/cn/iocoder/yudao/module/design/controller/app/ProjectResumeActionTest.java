package cn.iocoder.yudao.module.design.controller.app;
import cn.iocoder.yudao.module.design.project.DesignProjectService;
import cn.iocoder.yudao.module.infra.zhongshu.api.*;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;
class ProjectResumeActionTest {
    @Test void newerElevationAlwaysResumesItsOwnStateAndRetainsOlderResult() {
        var service=mock(DesignProjectService.class);
        var project=new DesignProjectService.ProjectSnapshot(91,901,"SELF_UPLOAD",null,"ELEVATION","ACTIVE");
        when(service.getProject(91,901)).thenReturn(Optional.of(project));
        when(service.activeSelectionCandidateId(91,"FLAT")).thenReturn(10L);
        when(service.activeSelectionCandidateId(91,"ELEVATION")).thenReturn(20L);
        when(service.latestResultVersionId(91)).thenReturn(30L);
        when(service.selectedJobId(91,"ELEVATION")).thenReturn(71L);
        when(service.jobImageOptions(72)).thenReturn(GenerationImageOptions.normalize("ELEVATION","4K","LANDSCAPE"));
        when(service.latestDesignInputs(91)).thenReturn(Map.of());
        var controller=new AppDesignProjectController();
        ReflectionTestUtils.setField(controller,"designProjectService",service);
        IdentitySessionPort identity=token -> Optional.of(new IdentitySessionPort.SessionContext(901,"fixture","fixture",false));
        ReflectionTestUtils.setField(controller,"identitySessionPort",identity);
        for(String status:List.of("QUEUED","RUNNING","VALIDATING","CANCEL_REQUESTED","SUCCEEDED","PARTIALLY_SUCCEEDED","FAILED","CANCELLED")) {
            when(service.latestJob(project)).thenReturn(Optional.of(new AiJobPort.JobView(72,901,status,2,0,0)));
            var vo=controller.getProject("91",null,"Bearer fixture").getData();
            String action=List.of("SUCCEEDED","PARTIALLY_SUCCEEDED").contains(status)?"SELECT_ELEVATION":List.of("FAILED","CANCELLED").contains(status)?"CREATE_ELEVATION_JOB":"POLL_JOB";
            assertThat(vo.getResumeAction()).as(status).isEqualTo(action);
            assertThat(vo.getJobId()).isEqualTo("72"); assertThat(vo.getResultVersionId()).isEqualTo("30");
            assertThat(vo.getAllowedActions()).contains(action,"VIEW_RESULT");
        }
        when(service.latestJob(project)).thenReturn(Optional.empty());
        assertThat(controller.getProject("91",null,"Bearer fixture").getData().getResumeAction()).isEqualTo("VIEW_RESULT");
        when(service.latestJob(project)).thenReturn(Optional.of(new AiJobPort.JobView(72,901,"SUCCEEDED",2,2,100)));
        when(service.selectedJobId(91,"ELEVATION")).thenReturn(72L);
        assertThat(controller.getProject("91",null,"Bearer fixture").getData().getResumeAction()).isEqualTo("VIEW_RESULT");
    }
}
