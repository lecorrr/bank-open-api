{{/* Resources are named after the release, so the Service of release "provider-app" is provider-app. */}}
{{- define "provider-app.name" -}}
{{- .Release.Name | trunc 63 | trimSuffix "-" -}}
{{- end -}}

{{- define "provider-app.version" -}}
{{- .Values.image.tag | default .Chart.AppVersion -}}
{{- end -}}

{{- define "provider-app.image" -}}
{{- printf "%s:%s" .Values.image.repository (include "provider-app.version" .) -}}
{{- end -}}

{{- define "provider-app.selectorLabels" -}}
app.kubernetes.io/name: {{ .Chart.Name }}
app.kubernetes.io/instance: {{ .Release.Name }}
{{- end -}}

{{- define "provider-app.labels" -}}
{{ include "provider-app.selectorLabels" . }}
app.kubernetes.io/version: {{ include "provider-app.version" . | quote }}
app.kubernetes.io/part-of: provider
app.kubernetes.io/managed-by: {{ .Release.Service }}
helm.sh/chart: {{ printf "%s-%s" .Chart.Name .Chart.Version }}
{{- end -}}
